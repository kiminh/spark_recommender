package com.samsung.vddil.recsys.feature

import scala.collection.mutable.HashMap
import com.samsung.vddil.recsys.feature.item.ItemFeatureExtractor
import com.samsung.vddil.recsys.ResourceStruct
import com.samsung.vddil.recsys.Pipeline
import com.samsung.vddil.recsys.linalg.Vector
import org.apache.spark.rdd.RDD
import com.samsung.vddil.recsys.feature.process.FeaturePostProcessor
import com.samsung.vddil.recsys.job.RecJob

/**
 * This data structure stores the information of feature
 */
trait FeatureStruct extends ResourceStruct{
    
    /**
     * The identity prefix of the feature 
     */
	def featureIden:String
	def resourcePrefix = featureIden
	
	/** this is the feature size*/
	def featureSize:Int
	/** this is the original feature size */
	def featureSizeOriginal:Int
	
	/**
	 * The resource string (identity plus parameter hash)
	 */
	def resourceStr:String
	
	/**
	 * The resource location 
	 */
	def featureFileName:String
	def resourceLoc = featureFileName 
	
	/**
	 * The feature names
	 */
	def featureMapFileName:String
	
	/**
	 * Feature parameters
	 */
	def featureParams:HashMap[String, String]
	
	/**
	 * Get the RDD data structure of the content. 
	 */
	def getFeatureRDD():RDD[(Int, Vector)] = {
	    Pipeline.instance.get.sc.objectFile[(Int, Vector)](featureFileName)
	}
	
	def getFeatureMapRDD():RDD[(Int, String)] = {
	    Pipeline.instance.get.sc.textFile(featureMapFileName).map{ line =>
	        val sp = line.split(",", 2)
	        (sp(0).toInt, sp(1))
	    }
	}
	
	/** A list of processors. */
	def featurePostProcessor:List[FeaturePostProcessor]
}

object FeatureStruct{
    def saveText_featureMapRDD(rdd: RDD[(Int, String)], fileName:String, jobInfo:RecJob) = {
        if (jobInfo.outputResource(fileName)){
        	rdd.map{pair =>
            	pair._1.toString + "," + pair._2
        	}.saveAsTextFile(fileName)
        }
    }
    
    def saveText_featureMapRDD(rdd: => RDD[(Int, (Int, String))], fileName:String, jobInfo:RecJob) = {
        if (jobInfo.outputResource(fileName)){
        	rdd.map{pair =>
	            pair._1.toString + "," + pair._2._1.toString + "," + pair._2._2
	        }.saveAsTextFile(fileName)
        }
    }
}


/**
 * The data structure of user feature 
 */
case class UserFeatureStruct(
				val featureIden:String, 
				val resourceStr:String,
				val featureFileName:String, 
				val featureMapFileName:String,
				val featureParams:HashMap[String, String],
				val featureSize:Int,
				val featureSizeOriginal:Int,
				val featurePostProcessor:List[FeaturePostProcessor]
			) extends FeatureStruct {
}

/**
 * The data structure of item feature
 * 
 *  @param extractor the feature extractor used to extract features from raw 
 *         data. This can be used for extracting features for cold start items. 
 */
case class ItemFeatureStruct(
				val featureIden:String,
				val resourceStr:String,
				val featureFileName:String, 
				val featureMapFileName:String,
				val featureParams:HashMap[String, String],
				val featureSize:Int,
				val featureSizeOriginal:Int,
				val featurePostProcessor:List[FeaturePostProcessor],
				val extractor:ItemFeatureExtractor,
				/** ItemFeatureStruct before current processing */
				val originalItemFeatureStruct:Option[ItemFeatureStruct]
			) extends FeatureStruct{
}
