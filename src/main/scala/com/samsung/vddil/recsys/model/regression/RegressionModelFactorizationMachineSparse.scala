package com.samsung.vddil.recsys.model.regression


import scala.collection.mutable.HashMap
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.mllib.regression.RidgeRegressionWithSGD
import org.apache.spark.mllib.regression.RidgeRegressionModel
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import com.samsung.vddil.recsys.job.RecJob
import com.samsung.vddil.recsys.model.ModelResource
import com.samsung.vddil.recsys.model.ModelProcessingUnit
import com.samsung.vddil.recsys.model.ModelStruct
import com.samsung.vddil.recsys.utils.Logger
import com.samsung.vddil.recsys.model._
import org.apache.spark.mllib.optimization.FactorizationMachineRegressionL2WithSGD
import org.apache.spark.mllib.optimization.FactorizationMachineRegressionModel
import com.samsung.vddil.recsys.Pipeline
import com.samsung.vddil.recsys.data.AssembledDataSet
import com.samsung.vddil.recsys.data.AssembledOnlineDataSet
import org.apache.spark.mllib.optimization.FactorizationMachineRegressionSparseWithSGD

object RegressionModelFactorizationMachineSparse 
	extends ModelProcessingUnit with RegCustomizedModel[FactorizationMachineRegressionModel] {
	
    def ParamLatentFactor = "latentFactor"
    def ParamL21Norm = "l21Param"
    
	//models...
	def learnModel(modelParams:HashMap[String, String], allData:AssembledDataSet, splitName:String, jobInfo:RecJob):ModelResource = {
		
        val dataResourceStr = allData.resourceStr
		
        // 1. Complete default parameters 
        val (numIterations, stepSizes, regParams) = getParameters(modelParams)
        val latentDim = Integer.valueOf(modelParams.getOrElseUpdate(ParamLatentFactor, "5"))
        val l21Param  = modelParams.getOrElseUpdate(ParamL21Norm, "0.01").toDouble
        
        // 2. Generate resource identity using resouceIdentity()
        val resourceIden = resourceIdentity(modelParams, dataResourceStr, splitName)
        var modelFileName = jobInfo.resourceLoc(RecJob.ResourceLoc_JobModel) + "/" + resourceIden
        
        //if the model is already there, we can safely return a fail. 
        if (jobInfo.jobStatus.resourceLocation_RegressModel.isDefinedAt(resourceIden))
            return ModelResource.fail
        
        // Generate and return a ModelResource that includes all resources. 
        val resourceMap:HashMap[String, Any] = new HashMap()
        if (jobInfo.outputResource(modelFileName)){
        	Logger.info("Training model...")
            
	        // 3. Model learning algorithms (HDFS operations)
        	val splitData = allData.getSplit(splitName).get 
	        val persistDataSets = allData.isInstanceOf[AssembledOnlineDataSet]
        	
	        //get the spark context
	        val sc = jobInfo.sc
	        
	        //parse the data to get Label and feature information in LabeledPoint form
	        val trainData = splitData.training.getLabelPointRDD
		    val testData  = splitData.testing.getLabelPointRDD
		    val valData   = splitData.validation.getLabelPointRDD
	        
		    if (persistDataSets){
		        trainData.persist
		        testData .persist
		        valData  .persist
		    }
	        
	        Logger.info("Training sample:" + trainData.count)
	        
	        //initial solution
	        
	        //creates a closure of training function 
	        val trainMeth = (input:RDD[LabeledPoint], numIterations:Int, stepSize:Double, regParam:Double) => 
	        					FactorizationMachineRegressionSparseWithSGD.train(
	        					        	input, latentDim, numIterations, stepSize, regParam, l21Param,  0.01)
	        					
	        //build model for each parameter combination
	        val bestModel = getBestModelByValidation(
	                                                trainMeth, trainData, 
	                                                valData, regParams, stepSizes, 
	                                                numIterations)
	        
	        //save best model found above
	        val modelStruct:CustomizedModelStruct[FactorizationMachineRegressionModel] = 
	             new CustomizedModelStruct[FactorizationMachineRegressionModel](IdenPrefix, resourceIden, 
	            		                            dataResourceStr,
	                                                modelFileName, modelParams, 
	                                                bestModel.get, allData.dimension)
	        
	        // 4. Compute training and testing error.
	        
	        //compute prediction on training data
	        val (trainMSE, testMSE) = trainNTestError(bestModel.get, trainData, testData)
	        modelStruct.performance(ModelStruct.PerformanceTrainMSE) = trainMSE
	        modelStruct.performance(ModelStruct.PerformanceTestMSE)  = testMSE
	        
	        if (persistDataSets){
	        	trainData.unpersist(false)
	        	testData.unpersist(false)
	        	valData.unpersist(false)
	        }
	        					
	        Logger.info("trainMSE = " + trainMSE + " testMSE = " + testMSE)
	        Logger.info("trainData: " + trainData.count + " testData: "
	                               + testData.count + " valData: " + valData.count)
	        modelStruct.saveModel()
	        resourceMap(ModelResource.ResourceStr_RegressModel) = modelStruct
	        Logger.info("Saved factorization machine model")
        }else{
            Logger.info("Loading model...")
            val modelStruct:CustomizedModelStruct[FactorizationMachineRegressionModel] = 
	             new CustomizedModelStruct[FactorizationMachineRegressionModel](IdenPrefix, resourceIden, 
	        		                            dataResourceStr,modelFileName, modelParams, allData.dimension)
	        		                            
	        resourceMap(ModelResource.ResourceStr_RegressModel) = modelStruct
	        Logger.info("Factorization machine model loaded. ")
        }

        new ModelResource(true, resourceMap, resourceIden)
	}
	
	val IdenPrefix:String = "RegModelRMFL2"
	
}