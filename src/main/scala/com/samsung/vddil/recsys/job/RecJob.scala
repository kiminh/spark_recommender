/*
 * Recommendation Job
 * 
 * @author jiayu.zhou
 * 
 */

package com.samsung.vddil.recsys.job

import com.samsung.vddil.recsys.Logger
import org.w3c.dom.{Document, Element, Node, NodeList}


case class RecJob (jobName:String, jobDesc:String, jobNode:Node) extends Job {
	
	//initialization 
    val jobType = JobType.Recommendation
    val featureList = populateFeatures()
    
    
    def populateFeatures():List[RecJobFeature] = {
      
      jobNode
      
      null
    }
    
    override def toString():String = {
       "Job:Recommendation  [" + this.jobName + "]"
    }
    
    def run():Unit= {
    	val logger = Logger.logger 
        
    	//TODO: consider cache each of these components. 
    	//      the cache may be 
    	
    	//preparing processing data. 
    	logger.info("**preparing processing data")
    	
    	//preparing features
    	logger.info("**preparing item features")
    	
    	logger.info("**preparing user features")
    	
    	logger.info("**preparing factorization features")
    	
    	//assemble training/validation/testing cases for training data. 
    	
    	
    	//learning models
    	
    	logger.info("**learning models")
    	
    	//testing recommendation performance. 
    	logger.info("**testing models")
    	
    	
    }
    
    sealed trait RecJobFeature  
    case class RecJobItemFeature(featureParm:Map[String, Any]) extends RecJobFeature
    case class RecJobUserFeature(featureParm:Map[String, Any]) extends RecJobFeature
    case class RecJobFactFeature(featureParm:Map[String, Any]) extends RecJobFeature
	
	sealed trait RecJobLearningMethod
}


