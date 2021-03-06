/**
 *
 */
package com.samsung.vddil.recsys.feature.item

import com.samsung.vddil.recsys.feature.FeatureProcessingUnit
import com.samsung.vddil.recsys.feature.FeatureResource
import com.samsung.vddil.recsys.job.RecJob
import com.samsung.vddil.recsys.linalg.Vector
import com.samsung.vddil.recsys.linalg.Vectors
import com.samsung.vddil.recsys.utils.HashString
import com.samsung.vddil.recsys.utils.Logger
import org.apache.spark.SparkContext._
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import scala.collection.mutable.HashMap
import com.samsung.vddil.recsys.feature.ItemFeatureStruct
import com.samsung.vddil.recsys.feature.ItemFeatureStruct
import scala.util.control.Breaks._
import com.samsung.vddil.recsys.feature.process.FeaturePostProcessor
import com.samsung.vddil.recsys.feature.process.FeaturePostProcess
import com.samsung.vddil.recsys.feature.FeatureStruct
import com.samsung.vddil.recsys.job.JobWithFeature
/**
 * @author jiayu.zhou
 *
 */
object ItemFeatureShowTime extends FeatureProcessingUnit with ItemFeatureExtractor {
    
    val ScheduleField_StartTime = 2
    val ScheduleField_EndTime   = 3
    val ScheduleField_ProgId    = 4
    
    val Param_TimeWindow = "timeWindow"
    val Param_TimeWindow_Default = "12"
        
    def getFeatureSources(dates:List[String], jobInfo:JobWithFeature):List[String] = {
    	dates.map{date =>
      		jobInfo.resourceLoc(RecJob.ResourceLoc_RoviHQ) + date + "/schedule*"
    	}.toList
    }
        
    def extractFeature(
            items:Set[String], featureSources:List[String],
    		featureParams:HashMap[String, String], featureMapFileName:String, 
    		sc:SparkContext): RDD[(String, Vector)] = {
        
        val timeWindow = featureParams.get(Param_TimeWindow).get.toInt
        val rangeSlot = Range(0, 2400, 2400/ timeWindow) :+ 100000 
        val bItemSet = sc.broadcast(items)
        val programTime: RDD[(String, Vector)] 
	        		= featureSources.map{scheduleFile => 
	            val schduleRDD = sc.textFile(scheduleFile).map{line=>
	                val fields = line.split('|')
	                val pid:String       = fields(ScheduleField_ProgId)
	                val timeStart:Int = fields(ScheduleField_StartTime).substring(8).toInt
	                val timeEnd:Int   = fields(ScheduleField_EndTime).substring(8).toInt
	                (pid, Set((timeStart, timeEnd)))
	            }.filter{ scheduleEntry =>
	                //filtering the programs 
	                bItemSet.value.contains(scheduleEntry._1)
	            }
	            schduleRDD
	        }.reduce{(a,b) => 
	            a.union(b) //concatenate all the records
	        }.reduceByKey{ (a, b) =>
	            a ++ b //reduce to group all starting/ending time. 
	        }.map{ line =>
	            val pid:String = line._1
	            val feature:Vector = generateFeatureVector(line._2, rangeSlot)
	            (pid, feature)
	        }
        
        programTime
    }
        
    def processFeature(
            featureParams:HashMap[String, String], 
            jobInfo:JobWithFeature):FeatureResource = {
        
        val trainCombData = jobInfo.jobStatus.resourceLocation_CombinedData_train.get
        
        //spark context 
        val sc = jobInfo.sc

        //1. Complete default parameters 
        val timeWindowStr = 
            featureParams.getOrElseUpdate(Param_TimeWindow, Param_TimeWindow_Default)
        val timeWindow = timeWindowStr.toInt
        
        //2. Generate resource identity
        val dataHasingStr = HashString.generateOrderedArrayHash(jobInfo.trainDates)
        val resourceIden = resourceIdentity(featureParams, dataHasingStr)
        
        val featureFileName    = jobInfo.resourceLoc(RecJob.ResourceLoc_JobFeature) + 
        							"/" + resourceIden
        var featureMapFileName = jobInfo.resourceLoc(RecJob.ResourceLoc_JobFeature) + 
        							"/" + resourceIden + "_Map"
        
        //2. Feature generation algorithm
        //get set of items 
        val itemSet = trainCombData.getItemList().collect.toSet
        
        
        
//        var fileName = jobInfo.resourceLoc(RecJob.ResourceLoc_RoviHQ) + 
//        			   jobInfo.trainDates(0) + "/schedule*"
        
        val scheduleFiles = jobInfo.trainDates.map{trainDate =>
            jobInfo.resourceLoc(RecJob.ResourceLoc_RoviHQ) + trainDate + "/schedule*"
        }
        			
        val rangeSlot = Range(0, 2400, 2400/ timeWindow) :+ 9999 
        
        // get feature map
        val featureMap = getFeatureMap(rangeSlot).toList
        if(jobInfo.outputResource(featureMapFileName)){
            FeatureStruct.saveText_featureMapRDD(sc.parallelize(featureMap), featureMapFileName, jobInfo)
        }
        
        //
        if(jobInfo.outputResource(featureFileName)){
	        // get features
            //the last one provides an inclusive upper bound. 
            
	        val bItemSet = sc.broadcast(itemSet) 
	        val programTime: RDD[(String, Vector)] 
	        		= scheduleFiles.map{scheduleFile => 
	            val schduleRDD = sc.textFile(scheduleFile).map{line=>
	                val fields = line.split('|')
	                val pid:String       = fields(ScheduleField_ProgId)
	                val timeStart:Int = fields(ScheduleField_StartTime).substring(8).toInt
	                val timeEnd:Int   = fields(ScheduleField_EndTime).substring(8).toInt
	                (pid, Set((timeStart, timeEnd)))
	            }.filter{ scheduleEntry =>
	                //filtering the programs 
	                bItemSet.value.contains(scheduleEntry._1)
	            }
	            schduleRDD
	        }.reduce{(a,b) => 
	            a.union(b) //concatenate all the records
	        }.reduceByKey{ (a, b) =>
	            a ++ b //reduce to group all starting/ending time. 
	        }.map{ line =>
	            val pid:String = line._1
	            val feature:Vector = generateFeatureVector(line._2, rangeSlot)
	            (pid, feature)
	        }
	        
	        val cnt = programTime.count
	        
	        // replace feature by feature id before output. 
	        val itemIdMap = trainCombData.getItemMap().collectAsMap
	        val bItemIdMap = sc.broadcast(itemIdMap)
	        
	        programTime.map{programTime =>
	            val item:Int = bItemIdMap.value(programTime._1)
	            val feature:Vector = programTime._2
	            (item, feature)
	        }.saveAsObjectFile(featureFileName)
	        Logger.info("Saved item features")
	        
        }
        val featureSize = sc.objectFile[(Int, Vector)](featureFileName).first._2.size 
        
        //post-processing.
        //TODO: Feature Selection 
	    val featurePostProcessor:List[FeaturePostProcessor] = List()
        val featureStruct:ItemFeatureStruct = 
            new ItemFeatureStruct(
                    IdenPrefix, resourceIden, featureFileName, 
                    featureMapFileName, featureParams, featureSize,
                    featureSize, featurePostProcessor, 
                    ItemFeatureShowTime, None)
        
        val resourceMap:HashMap[String, Any] = new HashMap()
        resourceMap(FeatureResource.ResourceStr_ItemFeature) = featureStruct
        resourceMap(FeatureResource.ResourceStr_FeatureDim)  = featureSize
        
        new FeatureResource(true, Some(resourceMap), resourceIden)
    }
    
    /**
     * generate show time features.
     */
    def generateFeatureVector(
            showTimeSet: Set[(Int, Int)], 
            rangeSlot:IndexedSeq[Int]): Vector ={
        
        val vectorLen = math.max(rangeSlot.size - 1, 1)
        //initialize
        val feature = Array.fill[Double](vectorLen)(0)
        
        showTimeSet.foreach{pair => 
            val startTime = pair._1
            breakable{ 
	            for ((thrVal, thrIdx) <- rangeSlot.zipWithIndex){
	                if (startTime < thrVal){
	                    feature(thrIdx-1) = 1
	                    break
	                }
	            }
            }
        }
        
        //create features.
        Vectors.sparse(feature)
    }
    
    def getFeatureMap(rangeSlot:IndexedSeq[Int]):Map[Int,String] = {
        var featureMap = Map[Int, String]()
        for (i <- (1 until rangeSlot.size)){
            val featureNameEntry:String = "TimeWindow["+rangeSlot(i-1).toString +"]["+ rangeSlot(i).toString +"]"
            featureMap = featureMap + (i -> featureNameEntry)
        }
        
        featureMap
    }
    
    val IdenPrefix:String = "ItemFeatureShowTime"
}