package com.samsung.vddil.recsys.feature

import scala.collection.mutable.HashMap
import com.samsung.vddil.recsys.feature.item.ItemFeatureExtractor
import com.samsung.vddil.recsys.ResourceStruct

/**
 * This data structure stores the information of feature
 */
trait FeatureStruct extends ResourceStruct{
    
    /**
     * The identity prefix of the feature 
     */
	def featureIden:String
	def resourcePrefix = featureIden
	
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
	
}

/**
 * The data structure of user feature 
 */
case class UserFeatureStruct(
				val featureIden:String, 
				val resourceStr:String,
				val featureFileName:String, 
				val featureMapFileName:String,
				val featureParams:HashMap[String, String]
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
				val extractor:ItemFeatureExtractor
			) extends FeatureStruct{
}
