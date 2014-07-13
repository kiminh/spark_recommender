package com.samsung.vddil.recsys.data

import scala.collection.mutable.HashSet
import scala.collection.mutable.HashMap
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.apache.spark.RangePartitioner
import org.apache.spark.HashPartitioner
import com.samsung.vddil.recsys.job.RecJob
import com.samsung.vddil.recsys.job.Rating
import com.samsung.vddil.recsys.job.RecJobStatus
import com.samsung.vddil.recsys.Logger
import com.samsung.vddil.recsys.utils.HashString
import com.samsung.vddil.recsys.feature.FeatureStruct
import com.samsung.vddil.recsys.linalg.Vector

/*
 * This is the object version of data assemble 
 */
object DataAssembleObj {
   
   /**
   * return join of features of specified IDs and ordering of features
   * 
   * @param idSet
   * @param usedFeature
   * @param featureResourceMap
   * @param sc SparkContext
   */
   def getCombinedFeatures(
		   idSet: RDD[String], 
		   usedFeatures: HashSet[String], 
           featureResourceMap: HashMap[String, FeatureStruct], 
           sc: SparkContext
       ): (RDD[(String, Vector)], List[String]) = 
   {
       val usedFeaturesList = usedFeatures.toList
       
       val idSetRDD = idSet.map(x => (x,1))
       
       //join all features RDD
       ///
       var featureJoin = sc.objectFile[(String, Vector)](featureResourceMap(usedFeaturesList.head).featureFileName).
       					 join(idSetRDD).map{
    	   					//x._1 => id, x._2._1 => feature vector, x._2._2 => 1
    	   					x=> (x._1, x._2._1)
       					 }
       ///remaining
	   for (usedFeature <- usedFeaturesList.tail){
		   featureJoin = featureJoin.join(
				sc.objectFile[(String, Vector)](featureResourceMap(usedFeature).featureFileName)
		   ).map{ x=>
		      (x._1, x._2._1 ++ x._2._2) //TODO: do we need to make sure this is a sparse vector? 
		   }
	   }
	   (featureJoin, usedFeaturesList)
   }
  
   /**
   * will return intersection of IDs for which features exist
   * 
   * @param usedFeatures features for which we want intersection of IDs
   * @param featureResourceMap contains mapping of features to actual files
   * @param sc SparkContext
   */
  def getIntersectIds(usedFeatures: HashSet[String], 
            featureResourceMap: HashMap[String, FeatureStruct], 
            sc: SparkContext):  RDD[String] = {
      
      val intersectIds = usedFeatures.map{feature =>
        sc.objectFile[(String, Vector)](featureResourceMap(feature).featureFileName)
          .map(_._1) //the first field is always id
      }.reduce((idSetA, idSetB) => idSetA.intersection(idSetB)) // reduce to get intersection of all sets
          
      intersectIds
  }
    
   /**
   * will return only those features which satisfy minimum coverage criteria
   * 
   * @param featureResourceMap contains map of features and location
   * @param minCoverage minimum coverage i.e. no. of features found should be greater than this pc
   * @param sc spark context
   * @param total number of items or users
   */
  def filterFeatures(featureResourceMap: HashMap[String, FeatureStruct], 
      minCoverage: Double, sc: SparkContext, total: Int) :HashSet[String] = {
    //set to keep keys of item feature having desired coverage
        var usedFeatures:HashSet[String] = new HashSet()

        //check each feature against minCoverage
        featureResourceMap foreach {
            case (k, v) =>
                {
                    val numFeatures = sc.objectFile[(String, Vector)](v.featureFileName).count
                    if ( (numFeatures.toDouble/total)  > minCoverage) {
                      //coverage satisfy by feature add it to used set
                        usedFeatures += k
                    }
                }
        }
        usedFeatures
   }
   
   /**
    * Join features and generate continuous data. The output is the location of the stored file.  
    * 
    * @param jobInfo 
    * @param minIFCoverage 
    * @param minUFCoverage 
    */
   def assembleContinuousData(jobInfo:RecJob, minIFCoverage:Double, minUFCoverage:Double ):String = {
      //1. inspect all available features
      //   drop features have low coverage (which significant reduces our training due to missing)
     
      //get spark context
      val sc = jobInfo.sc
      
      //get num of users
      val numUsers = jobInfo.jobStatus.users.length
      
      //get num of items
      val numItems = jobInfo.jobStatus.items.length
      
      
      //set to keep keys of item feature having desired coverage
      val usedItemFeature:HashSet[String] = filterFeatures(
                      jobInfo.jobStatus.resourceLocation_ItemFeature, 
                                                       minIFCoverage, 
                                                       sc, numItems)

      //set to keep keys of user feature having desired coverage
      val usedUserFeature:HashSet[String] = filterFeatures(
                            jobInfo.jobStatus.resourceLocation_UserFeature, 
                                                             minUFCoverage, 
                                                             sc, numItems)
    
      if (usedUserFeature.size == 0 || usedItemFeature.size == 0) {
          Logger.warn("Either user or item feature set is empty")
      }
                                                             
      //2. generate ID string 
      val dataHashingStr = HashString.generateOrderedArrayHash(jobInfo.trainDates)
      val resourceStr = assembleContinuousDataIden(dataHashingStr, usedUserFeature, usedItemFeature)
    
      val assembleFileName = jobInfo.resourceLoc(RecJob.ResourceLoc_JobData) + 
                                        "/" + resourceStr  + "_all"
      
      //check if the regression data has already generated in jobInfo.jobStatus
      //  it is possible this combination has been used (and thus generated) by other classifiers. 
      //  in that case directly return resourceStr.
      if (! jobInfo.jobStatus.resourceLocation_AggregateData_Continuous.isDefinedAt(resourceStr)) {
          
          //3. perform an intersection on selected item features, generate <intersectIF>
          val itemIntersectIds = getIntersectIds(usedItemFeature, 
                                               jobInfo.jobStatus.resourceLocation_ItemFeature, sc)
                                               
          //parse eligible features and extract only those with ids present in itemIntersectIds                  
          var (itemFeaturesRDD, itemFeatureOrder) =  
              getCombinedFeatures(itemIntersectIds, 
                                  usedItemFeature, 
                                  jobInfo.jobStatus.resourceLocation_ItemFeature, 
                                  sc)
          
                                  
          //4. perform an intersection on selected user features, generate <intersectUF>
          val userIntersectIds = getIntersectIds(usedUserFeature, 
                    jobInfo.jobStatus.resourceLocation_UserFeature, sc)
          
          //parse eligible features and extract only those with IDs present in userIntersectIds
          var (userFeaturesRDD, userFeatureOrder) = 
              getCombinedFeatures(
                      			  userIntersectIds, 
                                  usedUserFeature, 
                                  jobInfo.jobStatus.resourceLocation_UserFeature, 
                                  sc)
                                  
                                  
          //5. perform a filtering on ( UserID, ItemID, rating) using <intersectUF> and <intersectIF>, 
          //   and generate <intersectTuple>
          //filtering such that we have only user-item pairs such that for both features have been found
          val allData = sc.textFile(jobInfo.jobStatus.resourceLocation_CombineData)
                        .map{lines => 
                            val fields = lines.split(',')
                            //user, item, watchtime
                            (fields(0), (fields(1), fields(2).toDouble))
                        }//contains both user and item in set
          
          val filterByUser = allData.join(userIntersectIds.map(x=>(x,1)))
                                  .map {x => //(user, ((item, watchtime),1))
                                     (x._2._1._1, (x._1, x._2._1._2)) //(item, (user, watchtime))
                                   }
                                  
          val filterByUserItem = filterByUser.join(itemIntersectIds.map(x => (x,1)))
                                  .map { x => //(item, ((user, watchtime),1))
                                     (x._2._1._1, x._1, x._2._1._2) //(user, item, watchtime)
                                   }                        
                                                        
          //6. join features and <intersectTuple> and generate aggregated data (UF1 UF2 ... IF1 IF2 ... , feedback )
          //join with item features (join item first as # of items is small)
          val joinedItemFeatures = 
              filterByUserItem.map{x => 
                  (x._2, (x._1, x._3))
              }.join(itemFeaturesRDD 
              ).map{y => //(item, ((user, rating), IF))
                   val userID:String = y._2._1._1
                   val itemID:String = y._1
                   val itemFeature:Vector = y._2._2
                   val rating:Double = y._2._1._2
                   (userID, (itemID, itemFeature, rating))
              }
                                        
          val numExecutors = sc.getConf.getOption("spark.executor.instances")
          val numExecCores = sc.getConf.getOption("spark.executor.cores")
          val numPartitions = 2 * numExecutors.getOrElse("8").toInt * numExecCores.getOrElse("2").toInt
          
          //can use both range partitoner or hashpartitioner to efficiently partition by user
          
          val partedByUJoinedItemFeat = joinedItemFeatures.partitionBy(new RangePartitioner(numPartitions, 
                                                                                          joinedItemFeatures)) 

          //join with user features
          val joinedUserItemFeatures = 
              	partedByUJoinedItemFeat.join(userFeaturesRDD
              	).map {x=> //(user, ((item, IF, rating), UF))
                    //(user, item, UF, IF, rating)
                    val userID = x._1
                    val itemID = x._2._1._1
                    val userFeature:Vector = x._2._2
                    val itemFeature:Vector = x._2._1._2
                    val features = userFeature ++ itemFeature  
                    val rating:Double = x._2._1._3
                    (userID, itemID, features, rating)
                }                                                       
                                                                                          
          //7. save resource to <jobInfo.jobStatus.resourceLocation_AggregateData_Continuous>
          if (jobInfo.outputResource(assembleFileName)) {
        	  // join features and store in assembleFileName
        	  joinedUserItemFeatures.saveAsObjectFile(assembleFileName)
          }
          
          jobInfo.jobStatus.resourceLocation_AggregateData_Continuous(resourceStr) =  
                    DataSet(assembleFileName, userFeatureOrder, itemFeatureOrder)
          Logger.info("assembled features: " + assembleFileName)
      }
      
      resourceStr
   }
  
   def assembleContinuousDataIden(
      dataIdentifier:String,
      userFeature:HashSet[String], 
      itemFeature:HashSet[String]):String = {
    return "ContAggData_" + dataIdentifier+ 
          HashString.generateHash(userFeature.toString) + "_" + 
          HashString.generateHash(itemFeature.toString) 
   }
	
  def assembleBinaryData(jobInfo:RecJob, minIFCoverage:Double, minUFCoverage:Double):String = {
      //see assembleContinuousData
     throw new NotImplementedError("This function is yet to be implemented. ")
  }
  
  def assembleBinaryDataIden(
      dataIdentifier:String,
      userFeature:HashSet[String], 
      itemFeature:HashSet[String]):String = {
    return "BinAggData_" + dataIdentifier + 
          HashString.generateHash(userFeature.toString) + "_" + 
          HashString.generateHash(itemFeature.toString)
  }
}