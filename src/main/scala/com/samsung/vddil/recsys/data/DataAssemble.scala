package com.samsung.vddil.recsys.data


import com.samsung.vddil.recsys.job.RecJob
import com.samsung.vddil.recsys.job.Rating
import com.samsung.vddil.recsys.job.RecJobStatus
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import scala.collection.mutable.HashSet
import scala.collection.mutable.HashMap
import com.samsung.vddil.recsys.Logger
import com.samsung.vddil.recsys.utils.HashString

object DataAssemble {
	
	/**
	 * return join of features of specified Ids and ordering of features
	 */
	def getCombinedFeatures(idSet: Set[String], usedFeatures: HashSet[String], 
                              featureResourceMap: HashMap[String, String], 
                              sc: SparkContext): (RDD[(String, String)], List[String]) = {
		//initialize list of RDD of format (id,features)
		var idFeatures:List[RDD[(String, String)]]  = List.empty
		
		val usedFeaturesList = usedFeatures.toList
		
		//join all features RDD
		//add first feature to join
		var featureJoin = sc.textFile(featureResourceMap(usedFeaturesList.head))
              .map { line =>
                val fields = line.split(',')
                val id = fields(0)
                val features = fields.slice(1, fields.length).mkString(",")
                (id, features)
                }
              .filter(x => idSet.contains(x._1)) //id matches specified id
        
        //add remaining features
		for (usedFeature <- usedFeaturesList.tail) {
			featureJoin  = featureJoin.join( sc.textFile(featureResourceMap(usedFeature))
                			                    .map { line =>
                		                                val fields = line.split(',')
                		                                val id = fields(0)
                		                                val features = fields.slice(1, fields.length).mkString(",")
                		                                (id, features)
                                			    }
                                			    .filter(x => idSet.contains(x._1)) //id matches specified id
                                    		).map {x =>
                				                (x._1, x._2._1 + "," + x._2._2)
                			                }
		}
        (featureJoin, usedFeaturesList)
	}
	
	
	
	/**
	 * will return intersection of ids for which fetures exist
	 * usedFeatures: features for which we want intersection of ids
	 * featureResourceMap: contains mapping of features to actual files
	 * sc: SparkContext
	 */
	def getIntersectIds(usedFeatures: HashSet[String], 
			      featureResourceMap: HashMap[String, String], 
			      sc: SparkContext):  Set[String] = {
	    val usedFeaturesList = usedFeatures.toList
	    
	    //get Ids from first feature
		var intersectIds = sc.textFile(featureResourceMap(usedFeaturesList.head))
		                     .map{line  => 
		                         line.split(',')(0) //assuming first field is always id
	                          }
	    
	    //do intersection with other features
	    for (usedFeature <- usedFeaturesList.tail) {
	    	intersectIds = intersectIds.intersection(
	    			sc.textFile(featureResourceMap(usedFeature))
	    			  .map{line  => line.split(',')(0)}
	    			)
	    }
	    
	    intersectIds.collect.toSet
	}
	
	
	/**
	 * will return only those features which satisfy minimum coverage criteria
	 * featureResourceMap: contains map of features and location
	 * minCoverage: minimum coverage i.e. no. of features found should be greater than this pc
	 * sc: spark context
	 * total: number of items or users
	 */
	def filterFeatures(featureResourceMap: HashMap[String, String], 
			minCoverage: Double, sc: SparkContext, total: Int) :HashSet[String] = {
		//set to keep keys of item feature having desired coverage
        var usedFeatures:HashSet[String] = new HashSet()
        
        //check each feature against minCoverage
	    featureResourceMap foreach {
            case (k, v) =>
                {
                    val numFeatures = sc.textFile(v).count
                    if ( (numFeatures.toDouble/total)  > minCoverage) {
                    	//coverage satisfy by feature add it to used set
                        usedFeatures += k
                    }
                    
                }
	    }
        usedFeatures
	}
	
	
	/*
	 *  Joining features 
	 */
	def assembleContinuousData(jobInfo:RecJob, minIFCoverage:Double, minUFCoverage:Double ):String = {
		
	  
	    //1. inspect all available features
		//   drop features have low coverage (which significant reduces our training due to missing)
	    //   TODO: minUserFeatureCoverage and minItemFeatureCoverage from file. 
		
	  
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
                                                             
		//4. generate ID string 
		val resourceStr = assembleContinuousDataIden(usedUserFeature, usedItemFeature)
	  
		//check if the regression data has already generated in jobInfo.jobStatus
		//  it is possible this combination has been used (and thus generated) by other classifiers. 
		//  in that case directly return resourceStr. 
		if (! jobInfo.jobStatus.resourceLocation_AggregateData_Continuous.isDefinedAt(resourceStr)){
		
			//2. perform an intersection on selected user features, generate <intersectUF>
		    val userIntersectIds = getIntersectIds(usedUserFeature, 
		    		        jobInfo.jobStatus.resourceLocation_UserFeature, sc)
	  
		    //parse eligible features and extract only those with ids present in userIntersectIds
		    val (userFeaturesRDD, userFeatureOrder) =  getCombinedFeatures(userIntersectIds, 
            		    		                        usedUserFeature, 
            		    		                        jobInfo.jobStatus.resourceLocation_UserFeature, 
            		    		                        sc)
            		    		        
		    		        
			//3. perform an intersection on selected item features, generate <intersectIF>
		    val itemIntersectIds = getIntersectIds(usedItemFeature, 
                                                   jobInfo.jobStatus.resourceLocation_ItemFeature, sc)
                            
            //parse eligible features and extract only those with ids present in itemIntersectIds
            val (itemFeaturesRDD, itemFeatureOrder) =  getCombinedFeatures(itemIntersectIds, 
                                                        usedItemFeature, 
                                                        jobInfo.jobStatus.resourceLocation_ItemFeature, 
                                                        sc)
		  
			//5. perform a filtering on ( UserID, ItemID, rating) using <intersectUF> and <intersectIF>, 
			//   and generate <intersectTuple>
		    val filteredData = sc.textFile(jobInfo.jobStatus.resourceLocation_CombineData)
		                         .map{lines => 
		                        	  val fields = lines.split(',')
		                        	  //user, item, watchtime
		                        	  Rating(fields(0), fields(1), fields(2).toDouble)
		                             }
			                     .filter(x => userIntersectIds.contains(x.user)
			                    		       && itemIntersectIds.contains(x.item))
			   
			//6. join features and <intersectTuple> and generate aggregated data (UF1 UF2 ... IF1 IF2 ... , feedback )
			val aggData = filteredData.map{x => (x.user, (x.item, x.rating))}
			                          .join(userFeaturesRDD) // (user, ((item, rating), UF)) 
			                    	  .map {y =>
			                    	  	  //(item, (user, UF, rating))
			                    	      (y._2._1._1, (y._1, y._2._2, y._2._1._2)) 
			                    	   }
			                          .join(itemFeaturesRDD) //(item, ((user, UF, rating), IF))
			                          .map {z =>
			                          	  //user, item, UF, IF, rating
			                          	  z._2._1._1 + "," + z._1 + "," + 
			                          	  		z._2._1._2 + "," + z._2._2 + 
			                          	  		"," + z._2._1._3
			                          	  //UF, IF, rating
			                          	  //z._2._1._2 + "," + z._2._2 + "," 
			                          	  //+ z._2._1._3
			                           }
			
			//TODO: save the following ordering for later usage
			val ordering = userFeatureOrder.mkString(",") + ":" + itemFeatureOrder.mkString(",")
			 
			val assembleFileName = jobInfo.resourceLoc(RecJob.ResourceLoc_JobData) + "/" + resourceStr + "_all"
			
			// join features and store in assembleFileName
			aggData.saveAsTextFile(assembleFileName)
			
			//7. save resource to <jobInfo.jobStatus.resourceLocation_AggregateData_Continuous>
			jobInfo.jobStatus.resourceLocation_AggregateData_Continuous(resourceStr) = assembleFileName 
		}
		 
	    return resourceStr
	}
	
	def assembleContinuousDataIden(userFeature:HashSet[String], itemFeature:HashSet[String]):String = {
		return "ContAggData_" + HashString.generateHash(userFeature.toString) + "_"  + HashString.generateHash(itemFeature.toString) 
	}
	
	def assembleBinaryData(jobInfo:RecJob, minIFCoverage:Double, minUFCoverage:Double):String = {
	    //see assembleContinuousData
		return null
	}
	
	def assembleBinaryDataIden(userFeature:HashSet[String], itemFeature:HashSet[String]):String = {
		return "BinAggData_" + HashString.generateHash(userFeature.toString) + "_"  + HashString.generateHash(itemFeature.toString)
	}
}