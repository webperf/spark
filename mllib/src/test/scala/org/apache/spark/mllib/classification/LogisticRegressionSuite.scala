/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.mllib.classification

import scala.io.Source._
import scala.util.Random
import scala.collection.JavaConversions._

import org.scalatest.FunSuite
import org.scalatest.Matchers

import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.regression._
import org.apache.spark.mllib.util.LocalSparkContext
import org.apache.spark.mllib.util.TestingUtils._

object LogisticRegressionSuite {

  def generateLogisticInputAsList(
    offset: Double,
    scale: Double,
    nPoints: Int,
    seed: Int): java.util.List[LabeledPoint] = {
    seqAsJavaList(generateLogisticInput(offset, scale, nPoints, seed))
  }

  // Generate input of the form Y = logistic(offset + scale*X)
  def generateLogisticInput(
      offset: Double,
      scale: Double,
      nPoints: Int,
      seed: Int): Seq[LabeledPoint]  = {
    val rnd = new Random(seed)
    val x1 = Array.fill[Double](nPoints)(rnd.nextGaussian())

    val y = (0 until nPoints).map { i =>
      val p = 1.0 / (1.0 + math.exp(-(offset + scale * x1(i))))
      if (rnd.nextDouble() < p) 1.0 else 0.0
    }

    val testData = (0 until nPoints).map(i => LabeledPoint(y(i), Vectors.dense(Array(x1(i)))))
    testData
  }
}

class LogisticRegressionSuite extends FunSuite with LocalSparkContext with Matchers {

  // Test if we can correctly learn A, B where Y = logistic(A + B*X)
  test("logistic regression") {
    val nPoints = 10000
    val A = 2.0
    val B = -1.5

    val testData = LogisticRegressionSuite.generateLogisticInput(A, B, nPoints, 42)

    val testRDD = sc.parallelize(testData, 2)
    testRDD.cache()
    val lr = new LogisticRegressionWithSGD().setIntercept(true)
    lr.optimizer.setStepSize(10.0).setNumIterations(20)

    val model = lr.run(testRDD)

    // Test the weights
    val weight0 = model.weights(0)
    assert(weight0 >= -1.60 && weight0 <= -1.40, weight0 + " not in [-1.6, -1.4]")
    assert(model.intercept >= 1.9 && model.intercept <= 2.1, model.intercept + " not in [1.9, 2.1]")

    val validationData = LogisticRegressionSuite.generateLogisticInput(A, B, nPoints, 17)
    val validationRDD = sc.parallelize(validationData, 2)
    // Test prediction on RDD. At least 83% of the predictions should be on.
    validateCategoricalPrediction(model.predict(validationRDD.map(_.features)).collect(), validationData, 0.83)

    // Test prediction on Array. At least 83% of the predictions should be on.
    validateCategoricalPrediction(validationData.map(row => model.predict(row.features)), validationData, 0.83)
  }

  test("logistic regression with initial weights") {
    val nPoints = 10000
    val A = 2.0
    val B = -1.5

    val testData = LogisticRegressionSuite.generateLogisticInput(A, B, nPoints, 42)

    val initialB = -1.0
    val initialWeights = Vectors.dense(initialB)

    val testRDD = sc.parallelize(testData, 2)
    testRDD.cache()

    // Use half as many iterations as the previous test.
    val lr = new LogisticRegressionWithSGD().setIntercept(true)
    lr.optimizer.setStepSize(10.0).setNumIterations(10)

    val model = lr.run(testRDD, initialWeights)

    val weight0 = model.weights(0)
    assert(weight0 >= -1.60 && weight0 <= -1.40, weight0 + " not in [-1.6, -1.4]")
    assert(model.intercept >= 1.9 && model.intercept <= 2.1, model.intercept + " not in [1.9, 2.1]")

    val validationData = LogisticRegressionSuite.generateLogisticInput(A, B, nPoints, 17)
    val validationRDD = sc.parallelize(validationData, 2)
    // Test prediction on RDD. At least 83% of the predictions should be on.
    validateCategoricalPrediction(model.predict(validationRDD.map(_.features)).collect(), validationData, 0.83)

    // Test prediction on Array. At least 83% of the predictions should be on.
    validateCategoricalPrediction(validationData.map(row => model.predict(row.features)), validationData, 0.83)
  }

  test("multinomial logistic regression") {
    val seed = 12
    // We split the data into 60% of training set and 40% of testing set.
    val irisRDDs = sc.parallelize(loadIrisDataSet, 2).randomSplit(Array[Double](0.6, 0.4), seed)

    val trainRDD = irisRDDs(0)
    trainRDD.cache()

    val lr = new LogisticRegressionWithSGD().setIntercept(true).setNumOfClasses(3)
    lr.optimizer.setStepSize(10.0).setNumIterations(200)

    // Since the iris dataset is ill conditioned, without regularization, different optimizer will
    // return different result. As a result, we don't check the model coefficients here for now.
    val model = lr.run(trainRDD)

    val testRDD = irisRDDs(1)
    testRDD.cache()
    val testData = testRDD.collect()

    // Test prediction on RDD.
    assert(validateCategoricalPrediction(
      model.predict(testRDD.map(_.features)).collect(), testData, 0.95),
      "prediction accuracy should be at least higher than 95%")

    // Test prediction on Array.
    assert(validateCategoricalPrediction(
      testData.map(row => model.predict(row.features)), testData, 0.95),
      "prediction accuracy should be at least higher than 95%")
  }

  test("multinomial logistic regression with initial weights") {
    val seed = 12
    // We split the data into 60% of training set and 40% of testing set.
    val irisRDDs = sc.parallelize(loadIrisDataSet, 2).randomSplit(Array[Double](0.6, 0.4), seed)

    val trainRDD = irisRDDs(0)
    trainRDD.cache()

    val lr = new LogisticRegressionWithSGD().setIntercept(true).setNumOfClasses(3)
    lr.optimizer.setStepSize(10.0).setNumIterations(200)

    val random = new Random(seed)
    val initialWeights = Vectors.dense(Array.fill(10)(random.nextDouble()))
    val model = lr.run(trainRDD, initialWeights)
    // Since the iris dataset is ill conditioned, without regularization, different optimizer will
    // return different result. As a result, we don't check the model coefficients here for now.

    val testRDD = irisRDDs(1)
    testRDD.cache()
    val testData = testRDD.collect()

    // Test prediction on RDD.
    assert(validateCategoricalPrediction(
      model.predict(testRDD.map(_.features)).collect(), testData, 0.95),
      "prediction accuracy should be at least higher than 95%")

    // Test prediction on Array.
    assert(validateCategoricalPrediction(
      testData.map(row => model.predict(row.features)), testData, 0.95),
      "prediction accuracy should be at least higher than 95%")
  }
}
