package com.samsung.vddil.recsys.feature

import com.samsung.vddil.recsys.job.RecJob
import scala.collection.mutable.HashMap
import com.samsung.vddil.recsys.feature.fact.FactFeatureNMF
import com.samsung.vddil.recsys.feature.fact.FactFeaturePMF
import com.samsung.vddil.recsys.utils.HashString
import com.samsung.vddil.recsys.utils.Logger
import com.samsung.vddil.recsys.feature.process.FeaturePostProcess
import com.samsung.vddil.recsys.job.JobWithFeature

/*
 * This is the main entrance of the factorization feature processing.
 * 
 * TODO: change this component to dynamic class loading.
 */
object FactFeatureHandler extends FeatureHandler{
	val FFNMF = "nmf"
	val FFPMF = "pmf"
	
	def processFeature(
	        featureName:String, 
	        featureParams:HashMap[String, String], 
	        postProcessing:List[FeaturePostProcess], 
	        jobInfo:JobWithFeature):Boolean = {
		Logger.logger.info("Processing factorization feature [%s:%s]".format(featureName, featureParams))
		 
		var resource:FeatureResource = FeatureResource.fail
		
		//Process the features accordingly 
		featureName match{
		  case FFNMF => resource = FactFeatureNMF.processFeature(featureParams, jobInfo)
		  case FFPMF => resource = FactFeaturePMF.processFeature(featureParams, jobInfo)
		  case _ => Logger.logger.warn("Unknown item feature type [%s]".format(featureName))
		}
		
		//For the successful ones, push resource information to jobInfo.jobStatus.
		if(resource.success){
		  
		  //upon success, the Fact feature handler generates a user feature and an item feature.
		  
		   resource.resourceMap.get(FeatureResource.ResourceStr_ItemFeature) match{
		      case featureStruct:ItemFeatureStruct => 
		        var processedFeatureStruct = featureStruct
		        postProcessing.foreach{processUnit=>
		            processUnit.train(processedFeatureStruct).foreach{processor=>
		            	processedFeatureStruct = processor.processStruct(processedFeatureStruct, jobInfo)
		            }
		        }
		        
		        jobInfo.jobStatus.resourceLocation_ItemFeature(processedFeatureStruct.resourceIden) = processedFeatureStruct
		   }
		   
		   resource.resourceMap.get(FeatureResource.ResourceStr_UserFeature) match{
		      case featureStruct:UserFeatureStruct => 
		        var processedFeatureStruct = featureStruct
		        postProcessing.foreach{processUnit=>
		            processUnit.train(processedFeatureStruct).foreach{processor=>
		            	processedFeatureStruct = processor.processStruct(processedFeatureStruct, jobInfo)
		            }
		        }
		        jobInfo.jobStatus.resourceLocation_UserFeature(processedFeatureStruct.resourceIden) = processedFeatureStruct
		   }
		   
		}
		
		resource.success
	}
}