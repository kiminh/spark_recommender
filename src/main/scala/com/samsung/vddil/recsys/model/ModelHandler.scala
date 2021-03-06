package com.samsung.vddil.recsys.model

import com.samsung.vddil.recsys.job.RecJob
import scala.collection.mutable.HashMap
import com.samsung.vddil.recsys.utils.Logger
import com.samsung.vddil.recsys.model.regression.RegressionModelRidge
import com.samsung.vddil.recsys.model.regression.RegressionModelFactorizationMachine
import com.samsung.vddil.recsys.data.AssembledDataSet
import com.samsung.vddil.recsys.model.regression.RegressionModelFactorizationMachineSparse
import com.samsung.vddil.recsys.model.regression.RegressionModelFeatureLearningFactMachine

/**
 * The model handler implementations are used to process a specified type of model.  
 * 
 * @author jiayu.zhou
 */
trait ModelHandler {
	/*
	 * This method process one type of model, and return a boolean value indicating if the model 
	 * is built successfully or failed. For the successful ones, this method should push resource 
	 * information to jobInfo.jobStatus 
	 */
	def buildModel(
	        modelName:String, 
	        modelParam:HashMap[String, String], 
	        allData:AssembledDataSet,
	        splitName: String,
	        jobInfo:RecJob): Boolean
}


object RegressionModelHandler extends ModelHandler {
	//predefined values for model name
	val RegModelRidge:String = "ridge_reg"
	val RegModelLasso:String = "lasso_reg"
	val RegModelFML2:String  = "fm_l2_reg"
	val RegModelFML21:String  = "fm_l21_reg"
	val RegModelFM_FL:String  = "fm_fl_reg"
	
	def buildModel(
	        modelName:String, 
	        modelParams:HashMap[String, String], 
	        allData:AssembledDataSet,
	        splitName: String,
	        jobInfo:RecJob
	    ): Boolean = {
	    
		Logger.info("Processing regression model [%s:%s]".format(modelName, modelParams))
	  
	    var resource:ModelResource = ModelResource.fail
		modelName match{
		  case RegModelRidge => resource = RegressionModelRidge.learnModel(modelParams, allData, splitName, jobInfo)
		  //case RegModelLasso => resource = ModelResource.fail
		  case RegModelFML2  => resource = RegressionModelFactorizationMachine.learnModel(modelParams, allData, splitName, jobInfo)
		  case RegModelFML21 => resource = RegressionModelFactorizationMachineSparse.learnModel(modelParams, allData, splitName, jobInfo)
		  case RegModelFM_FL => resource = RegressionModelFeatureLearningFactMachine.learnModel(modelParams, allData, splitName, jobInfo)
		  case _ => Logger.warn("Unknown regression model name [%s]".format(modelName))
		}
	  
		//For the successful ones, push resource information to jobInfo.jobStatus
		if(resource.success){
		   resource.resourceMap(ModelResource.ResourceStr_RegressModel) match{
		     case model:ModelStruct => 
		       jobInfo.jobStatus.resourceLocation_RegressModel(resource.resourceIden) = model
		   }
		}
	    
		// return if the resource is successful or not.
	    resource.success
	}
}

object ClassificationModelHandler extends ModelHandler {
	val ClsModelLogisticL2:String = "lrl2_cls"
	val ClsModelLogisticL1:String = "lrl1_cls"
  
	def buildModel(
	        modelName:String, 
	        modelParams:HashMap[String, String], 
	        allData:AssembledDataSet,
	        splitName: String, 
	        jobInfo:RecJob): Boolean = {
	    
		Logger.info("Processing classification model [%s:%s]".format(modelName, modelParams))
	  
	    var resource:ModelResource = ModelResource.fail
		modelName match{
		  //case ClsModelLogisticL2 => resource = ModelResource.fail
		  //case ClsModelLogisticL1 => resource = ModelResource.fail
		  case _ => Logger.warn("Unknown classification model name [%s]".format(modelName))
		}
	  
		//For the successful ones, push resource information to jobInfo.jobStatus
		if(resource.success){
		   resource.resourceMap(ModelResource.ResourceStr_ClassifyModel) match{
		     case model:ModelStruct => 
		       jobInfo.jobStatus.resourceLocation_ClassifyModel(resource.resourceIden) = model
		   }
		}
	    
		// return if the resource is successful or not.
	    resource.success
	}
}
