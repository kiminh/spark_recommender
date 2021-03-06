package com.samsung.vddil.recsys.data

import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet
import org.apache.spark.HashPartitioner
import org.apache.spark.RangePartitioner
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import com.samsung.vddil.recsys.utils.Logger
import com.samsung.vddil.recsys.feature.FeatureStruct
import com.samsung.vddil.recsys.job.Rating
import com.samsung.vddil.recsys.job.RecJob
import com.samsung.vddil.recsys.job.RecJobStatus
import com.samsung.vddil.recsys.linalg.Vector
import com.samsung.vddil.recsys.linalg.SparseVector
import com.samsung.vddil.recsys.Pipeline
import com.samsung.vddil.recsys.utils.HashString

/**
 * This is the object version of data assemble. During the data assembling, features are
 * stored in the data structure of [[com.samsung.vddil.recsys.linalg.Vector]]
 */
object DataAssemble {
   
    /**
     * From a given list of features, we re-assemble the (id, features). 
     */
   def reassembleFeatures(
        idSet: RDD[Int],
		featuresStructs: List[FeatureStruct]
   ):RDD[(Int, Vector)] = {
       
       val idSetRDD = idSet.map(x => (x,1))
       
       var featureJoin = featuresStructs.head.getFeatureRDD.
    		   join(idSetRDD).map{x=>  // (ID, (feature, 1))
                   val ID = x._1 // could be both user ID and item ID
                   val feature:Vector = x._2._1
                   (ID, feature)
       }
       
       for (usedFeature <- featuresStructs.tail){
	       featureJoin = featureJoin.join(
	            usedFeature.getFeatureRDD
	       ).map{ x => // (ID, feature1, feature2)
		      val ID = x._1
		      val concatenateFeature:Vector = x._2._1 ++ x._2._2 
		      (ID, concatenateFeature) //TODO: do we need to make sure this is a sparse vector? 
		   }
	   }
       featureJoin
   }
   
    
   /**
   * Returns join of features of specified IDs, and ordering of features
   * 
   * @param idSet the ID (user ID or item ID) to be used in join
   * @param usedFeature the feature resource identity
   * @param sc SparkContext the SparkContext used to 
   * 
   * @return a tuple of (joined features, used feature list), the former is an concatenated vector,
   *         and the latter is a list of strings representing the order of features used in the concatenation. 
   */
   def getCombinedFeatures(
		   idSet: RDD[Int], 
		   usedFeatures: HashSet[FeatureStruct], 
           sc: SparkContext
       ): (RDD[(Int, Vector)], List[FeatureStruct]) = 
   {
       val usedFeaturesList = usedFeatures.toList
       
       Logger.info("Length of used features: " + usedFeaturesList.length)       
       val idSetRDD = idSet.map(x => (x,1))
       
       //join all features RDD
       ///the first join. 
       var featureJoin = usedFeaturesList.head.getFeatureRDD.
    		   join(idSetRDD).map{x=>  // (ID, (feature, 1))
                   val ID = x._1 // could be both user ID and item ID
                   val feature:Vector = x._2._1
                   (ID, feature)
       }
       
       ///remaining
	   for (usedFeature <- usedFeaturesList.tail){
	       featureJoin = featureJoin.join(
	            usedFeature.getFeatureRDD
	       ).map{ x => // (ID, feature1, feature2)
		      val ID = x._1
		      val concatenateFeature:Vector = x._2._1 ++ x._2._2 
		      (ID, concatenateFeature) //TODO: do we need to make sure this is a sparse vector? 
		   }
	   }
	   
	   (featureJoin, usedFeaturesList)
   }
  
   /**
   * Returns intersection of IDs for which features exist
   * 
   * @param usedFeatures features for which we want intersection of IDs
   * @param featureResourceMap contains mapping of features to actual files
   * @param sc SparkContext
   * 
   * @return an RDD of IDs. 
   */
  def getIntersectIds(
            usedFeatures: HashSet[FeatureStruct], 
            sc: SparkContext):  RDD[Int] = {
      
      val intersectIds = usedFeatures.map{feature =>
        feature.getFeatureRDD
          .map(_._1) //the first field is always id
      }.reduce((idSetA, idSetB) => idSetA.intersection(idSetB)) // reduce to get intersection of all sets
          
      intersectIds
  }
    
   /**
   * Returns only those features which satisfy minimum coverage criteria
   * 
   * @param featureResourceMap contains map of features and location
   * @param minCoverage minimum coverage i.e. no. of features found should be greater than this pc
   * @param sc spark context
   * @param total number of items or users
   * 
   * @return the features with the specified minimum item/user coverage
   */
  def filterFeatures(featureResourceMap: HashMap[String, FeatureStruct], 
      minCoverage: Double, sc: SparkContext, total: Int) 
   		:HashSet[FeatureStruct] = {
    //set to keep keys of item feature having desired coverage
        var usedFeatures:HashSet[FeatureStruct] = new HashSet()

        //check each feature against minCoverage
        featureResourceMap foreach {
            case (k, v) =>
                {
                    val numFeatures = v.getFeatureRDD.count
                    if ( (numFeatures.toDouble/total)  > minCoverage) {
                      //coverage satisfy by feature add it to used set
                        usedFeatures += v
                    }
                }
        }
        usedFeatures
   }
  

   /**
    * Joins features and generates continuous data, and returns the resource identity.  
    * 
    * A data structure is stored in the HashMap 
    * `jobInfo.jobStatus.resourceLocation_AggregateData_Continuous` with 
    * the resource identity as the key. 
    * The data is serialized and stored in HDFS. The serialization file  
    * has the type of (userID:String, itemID:String, features:Vector, rating:Double)
    * 
    * @param jobInfo the job information
    * @param minIFCoverage minimum item feature coverage 
    * @param minUFCoverage minimum user feature coverage
    * @param onlineData if true then the features are not concatenated 
    *        before its use, and assembled data will not be dumped to file systems. 
    * @param plainTextOutput *additional* plain text output.  
    * 
    * @return the resource identity of the assembled data
    */
   def assembleContinuousData(
           jobInfo:RecJob, minIFCoverage:Double, minUFCoverage:Double,
           onlineData: Boolean,
           plainTextOutput:Boolean
   ):AssembledDataSet = {
      require(minIFCoverage >= 0 && minIFCoverage <= 1)
      require(minUFCoverage >= 0 && minUFCoverage <= 1)
      
      val combData:CombinedDataSet = jobInfo.jobStatus.resourceLocation_CombinedData_train.get
      val itemFeatureList = jobInfo.jobStatus.resourceLocation_ItemFeature
      val userFeatureList = jobInfo.jobStatus.resourceLocation_UserFeature
      
      //1. inspect all available features
      //   drop features have low coverage (which significant reduces our training due to missing)
      
      //get spark context
      val sc = jobInfo.sc
      
      //get num of users
      val numUsers = combData.userNum
      
      //get num of items
      val numItems = combData.itemNum
      
      //set to keep keys of item feature having desired coverage
      val usedItemFeature:HashSet[FeatureStruct] = filterFeatures(
                      itemFeatureList, minIFCoverage, sc, numItems)

      //set to keep keys of user feature having desired coverage
      val usedUserFeature:HashSet[FeatureStruct] = filterFeatures(
                      userFeatureList, minUFCoverage, sc, numItems)
    
      if (usedUserFeature.size == 0 || usedItemFeature.size == 0) {
          Logger.warn("Either user or item feature set is empty")
      }
                                                             
      //2. generate ID string 
      val dataHashingStr = HashString.generateOrderedArrayHash(combData.dates)
      val resourceStr = assembleContinuousDataIden(dataHashingStr, usedUserFeature, usedItemFeature)
    
      val assembleFileName = jobInfo.resourceLoc(RecJob.ResourceLoc_JobData) + 
                                        "/" + resourceStr  + "_all"
      
      val assembleCombDataResourceStr = resourceStr + "_combData"
      val assembleCombDataFileName    = jobInfo.resourceLoc(RecJob.ResourceLoc_JobData) + "/" + assembleCombDataResourceStr  + "_all" 
                                        
                                        
      //check if the regression data has already generated in jobInfo.jobStatus
      //  it is possible this combination has been used (and thus generated) by other classifiers. 
      //  in that case directly return resourceStr.
      if (! jobInfo.jobStatus.resourceLocation_AggregateData_Continuous.isDefinedAt(resourceStr)) {
          
          //3. perform an intersection on selected item features, generate <intersectIF>
          val itemIntersectIds = getIntersectIds(usedItemFeature, sc)
                                               
          //parse eligible features and extract only those with ids present in itemIntersectIds                  
          var (itemFeaturesRDD, itemFeatureOrder) =  
              getCombinedFeatures(itemIntersectIds, usedItemFeature, sc)
          
                                  
          //4. perform an intersection on selected user features, generate <intersectUF>
          val userIntersectIds = getIntersectIds(usedUserFeature, sc)
          
          //parse eligible features and extract only those with IDs present in userIntersectIds
          var (userFeaturesRDD, userFeatureOrder) = 
              getCombinedFeatures(userIntersectIds, usedUserFeature, sc)
                                  
          
          //5. perform a filtering on ( UserID, ItemID, rating) using <intersectUF> and <intersectIF>, 
          //   and generate <intersectTuple>
          //filtering such that we have only user-item pairs such that for both features have been found
          val allData = sc.textFile(combData.resourceLoc)
                        .map{lines => 
                            val fields = lines.split(',')
                            //user, item, watchtime
                            (fields(0).toInt, (fields(1).toInt, fields(2).toDouble))
                        }//contains both user and item in set
          
          val filterByUser = allData.join(userIntersectIds.map(x=>(x,1))
                  ).map {x => //(user, ((item, watchtime),1))
                      val itemID    = x._2._1._1
                      val userID    = x._1
                      val watchTime = x._2._1._2
                      (itemID, (userID, watchTime)) 
                  }
                                  
          val filterByUserItem = filterByUser.join(itemIntersectIds.map(x => (x,1))
                  ).map { x => //(item, ((user, watchtime),1))
                      val userID    = x._2._1._1
                      val itemID    = x._1
                      val watchTime = x._2._1._2
                      (userID, itemID, watchTime) 
                  }                        
          
          val assembleCombData:CombinedDataSet = combData.createSubset(assembleCombDataResourceStr, assembleCombDataFileName)
          
          if (! assembleCombData.resourceExist){
              if (jobInfo.outputResource(assembleCombDataFileName)) { assembleCombData.saveDataRDD(filterByUserItem) }
          }
          
          
          if(plainTextOutput){
              val plainTextOutputUserItemMatrix = jobInfo.resourceLoc(RecJob.ResourceLoc_JobData) + 
                                        		     "/" + resourceStr  + "_plainText_userItemMatrix"
              val plainTextOutputItemFeature    = jobInfo.resourceLoc(RecJob.ResourceLoc_JobData) + 
                                        		     "/" + resourceStr  + "_plainText_ItemFeatureMatrix"
              
              if(jobInfo.outputResource(plainTextOutputUserItemMatrix)){
	              //output data matrix. 
	              filterByUserItem.map{line => 
	                  val userId:Int    = line._1
	                  val itemId:Int    = line._2
	                  val rating:Double = line._3
	                  (userId, (itemId, rating))
	              }.groupByKey().map{line => //Int, Iterable[(Int, Double)]
	                  val userId:Int    = line._1
	                  val userHistory   = line._2.toList.mkString("%")
	                  userId.toString + "@" + userHistory
	              }.saveAsTextFile(plainTextOutputUserItemMatrix)
              }
                                        		     
              if(jobInfo.outputResource(plainTextOutputItemFeature)){
	        	  //output feature matrix
	              itemFeaturesRDD.map{line =>
	                  val itemId:Int = line._1
	                  val itemFeatureDS = line._2.toSparse.data
	                  val itemFeature   = itemFeatureDS.index.zip(itemFeatureDS.data).mkString("%")  
	                  
	                  //(itemId, itemFeature)
	                  itemId.toString + "@" + itemFeature
	              }.saveAsTextFile(plainTextOutputItemFeature)
              }
          } 
          
          //6. Construct assembled data set using online/offline mode. 
          if (onlineData){
              //ONLINE MODE. No dump. 
              jobInfo.jobStatus.resourceLocation_AggregateData_Continuous(resourceStr) 
	          	= new AssembledOnlineDataSet(
	          	        resourceStr,  
	          	        userFeatureOrder, itemFeatureOrder, 
	          	        assembleCombData, filterByUserItem.count)  
              
          }else{
              //OFFLINE MODE.    
	          //join features and <intersectTuple> and generate aggregated data (UF1 UF2 ... IF1 IF2 ... , feedback )
	          //join with item features (join item first as # of items is small)
	          val joinedItemFeatures = 
	              filterByUserItem.map{x => 
	                  (x._2, (x._1, x._3))
	              }.join(itemFeaturesRDD 
	              ).map{y => //(item, ((user, rating), IF))
	                   val userID:Int = y._2._1._1
	                   val itemID:Int = y._1
	                   val itemFeature:Vector = y._2._2
	                   val rating:Double = y._2._1._2
	                   (userID, (itemID, itemFeature, rating))
	              }
	                                        
	          
	          //can use both range partitoner or hashpartitioner to efficiently partition by user
	          //val numPartitions = jobInfo.partitionNum_train
	          val partedByUJoinedItemFeat = joinedItemFeatures//.partitionBy(
	                                          //new RangePartitioner(numPartitions, 
	                                          //                    joinedItemFeatures)) 
	
	          //join with user features
	          val joinedUserItemFeatures = 
	              	partedByUJoinedItemFeat.join(userFeaturesRDD
	              	).map {x=> //(user, ((item, IF, rating), UF))
	                    //(user, item, UF, IF, rating)
	                    val userID = x._1
	                    val itemID = x._2._1._1
	                    val userFeature:SparseVector = x._2._2.toSparse
	                    val itemFeature:SparseVector = x._2._1._2.toSparse
	                    val features = userFeature ++ itemFeature  
	                    val rating:Double = x._2._1._3
	                    (userID, itemID, features, rating)
	                }                                                       
	                                                                                          
	          //7. save resource to <jobInfo.jobStatus.resourceLocation_AggregateData_Continuous>
	          if (jobInfo.outputResource(assembleFileName)) {
	        	  // join features and store in assembleFileName
	        	  joinedUserItemFeatures.saveAsObjectFile(assembleFileName)
	          }
	          
	          jobInfo.jobStatus.resourceLocation_AggregateData_Continuous(resourceStr) 
	          	= new AssembledOfflineDataSet(
	          	        resourceStr, assembleFileName, 
	          	        userFeatureOrder, itemFeatureOrder, 
	          	        assembleCombData, joinedUserItemFeatures.count)  
	                    
	          Logger.info("offline assembled features: " + assembleFileName)
	          //Logger.info("Total data size: " + sampleSize)

          }
          
          val dataDimension = 
              jobInfo.jobStatus.resourceLocation_AggregateData_Continuous(resourceStr).
              getLabelPointRDD.first.features.size
          
          jobInfo.jobStatus.resourceLocation_AggregateData_Continuous(resourceStr).dimension = dataDimension  
          
      }
      
      jobInfo.jobStatus.resourceLocation_AggregateData_Continuous(resourceStr)
   }
   
   /**
    * Returns the unique identity string of the continuous assemble data structure
    * 
    *  The string can be used as key for resource map storing this data, 
    *  as well as the file name for storing the data in file system. 
    *  
    *  An example of data identifier is 
    * {{{
    * val dataIdentifier = HashString.generateOrderedArrayHash(jobInfo.trainDates) 
    * }}}
    * @param dataIdentifier 
    * @param userFeature a set of user features
    * @param itemFeature a set of item features 
    * @return a string uniquely identifies the assemble data
    */
   def assembleContinuousDataIden(
      dataIdentifier:String,
      userFeature:HashSet[FeatureStruct], 
      itemFeature:HashSet[FeatureStruct]):String = {
       
      val userFeatureStr = userFeature.map{featureStruct =>
          featureStruct.featureIden
      }.mkString("%")
      
      val itemFeatureStr = itemFeature.map{featureStruct =>
          featureStruct.featureIden
      }.mkString("%")
       
    return "ContAggData_" + dataIdentifier+ 
           "_" +  HashString.generateHash(userFeatureStr + "_" + itemFeatureStr) 
   }
	
  def assembleBinaryData(jobInfo:RecJob, minIFCoverage:Double, minUFCoverage:Double):AssembledDataSet = {
      //see assembleContinuousData
     throw new NotImplementedError("This function is yet to be implemented. ")
  }
  
  /**
    * Returns the unique identity string of the binary assemble data structure
    * 
    *  The string can be used as key for resource map storing this data, 
    *  as well as the file name for storing the data in file system.  
    * 
    * An example of data identifier is 
    * {{{
    * val dataIdentifier = HashString.generateOrderedArrayHash(jobInfo.trainDates) 
    * }}}
    * 
    * @param dataIdentifier 
    * @param userFeature a set of user features
    * @param itemFeature a set of item features 
    * @return a string uniquely identifies the assemble data
   */
  def assembleBinaryDataIden(
      dataIdentifier:String,
      userFeature:HashSet[String], 
      itemFeature:HashSet[String]):String = {
    return "BinAggData_" + dataIdentifier + 
          HashString.generateHash(userFeature.toString) + "_" + 
          HashString.generateHash(itemFeature.toString)
  }
}
