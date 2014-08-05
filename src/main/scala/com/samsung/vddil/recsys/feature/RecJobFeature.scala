package com.samsung.vddil.recsys.feature

import com.samsung.vddil.recsys.job.RecJob
import scala.collection.mutable.HashMap

/**
 * The recommendation job feature data structure.  
 * 
 * {{{
 * new RecJobUserFeature("Zapping", (freq -> 10)) 
 * new RecJobFactFeature("PMF", (k -> 10, pass -> 1))
 * }}}
 */
sealed trait RecJobFeature{
    /** the feature name used to invoke different feature extraction algorithm */
    def featureName:String
    
    /** feature extraction parameters */
    def featureParams:HashMap[String, String]
    
    /** Extracts features and store (extracted) feature information in jobStatus */
	def run(jobInfo: RecJob):Unit
}

/** Item feature (program feature) e.g., genre  */
case class RecJobItemFeature(featureName:String, featureParams:HashMap[String, String]) extends RecJobFeature{
	def run(jobInfo: RecJob) = {
	   jobInfo.jobStatus.completedItemFeatures(this) = ItemFeatureHandler.processFeature(featureName, featureParams, jobInfo)
	}
}

/** User feature e.g., watch time, zapping */
case class RecJobUserFeature(featureName:String, featureParams:HashMap[String, String]) extends RecJobFeature{
	def run(jobInfo: RecJob) = {
	   jobInfo.jobStatus.completedUserFeatures(this) = UserFeatureHandler.processFeature(featureName, featureParams, jobInfo)
	}
}

/** Factorization-based (collaboration filtering) features. */
case class RecJobFactFeature(featureName:String, featureParams:HashMap[String, String]) extends RecJobFeature{
	def run(jobInfo: RecJob) = {
	    jobInfo.jobStatus.completedFactFeatures(this) = FactFeatureHandler.processFeature(featureName, featureParams, jobInfo)
	}
}