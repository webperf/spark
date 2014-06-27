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

package org.apache.spark.mllib.optimization

import breeze.linalg.{axpy => brzAxpy, DenseVector => BDV}

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.mllib.linalg.{Vectors, Vector}

/**
 * :: DeveloperApi ::
 * Class used to compute the gradient for a loss function, given a single data point.
 */
@DeveloperApi
abstract class Gradient extends Serializable {
  /**
   * Compute the gradient and loss given the features of a single data point.
   *
   * @param data features for one data point
   * @param label label for this data point
   * @param weights weights/coefficients corresponding to features
   *
   * @return (gradient: Vector, loss: Double)
   */
  def compute(data: Vector, label: Double, weights: Vector): (Vector, Double)

  /**
   * Compute the gradient and loss given the features of a single data point,
   * add the gradient to a provided vector to avoid creating new objects, and return loss.
   *
   * @param data features for one data point
   * @param label label for this data point
   * @param weights weights/coefficients corresponding to features
   * @param cumGradient the computed gradient will be added to this vector
   *
   * @return loss
   */
  def compute(data: Vector, label: Double, weights: Vector, cumGradient: Vector): Double
}

/**
 * :: DeveloperApi ::
 * Compute gradient and loss for a multinomial logistic loss function,
 * as used in multi-class classification (it is also used in binary logistic regression).
 * See also the documentation for the precise formulation.
 */
@DeveloperApi
class LogisticGradient extends Gradient {
  override def compute(data: Vector, label: Double, weights: Vector): (Vector, Double) = ???

  override def compute(
      data: Vector,
      label: Double,
      weights: Vector,
      cumGradient: Vector): Double = {
    def alpha(i: Int): Int = if (i == 0) 1 else 0
    def delta(i: Int, j: Int): Int = if (i == j) 1 else 0

    val brzData = data.toBreeze
    val brzWeights = weights.toBreeze
    val brzCumGradient = cumGradient.toBreeze

    assert((brzWeights.length % brzData.length) == 0)
    assert(cumGradient.toBreeze.length == brzWeights.length)

    val nClasses = (brzWeights.length / brzData.length) + 1
    val classLabel = math.round(label).toInt

    var denominator = 1.0
    val numerators = Array.ofDim[Double](nClasses - 1)

    var i = 0
    while (i < nClasses - 1) {
      var acc = 0.0
      brzData.activeIterator.foreach {
        case (_, 0.0) => // Skip explicit zero elements.
        case (j, value) => acc += value * brzWeights((i * brzData.length) + j)
      }
      numerators(i) = math.exp(acc)
      denominator += numerators(i)
      i += 1
    }

    i = 0
    while (i < nClasses - 1) {
      brzData.activeIterator.foreach {
        case (_, 0.0) => // Skip explicit zero elements.
        case (j, value) => brzCumGradient(i * data.toBreeze.length + j) -=
          ((1 - alpha(classLabel)) * delta(classLabel, i + 1) - numerators(i) / denominator) *
            brzData(j)
      }
      i += 1
    }

    classLabel match {
      case 0 => -math.log(1.0 / denominator)
      case _ => -math.log(numerators(classLabel - 1) / denominator)
    }
  }
}

/**
 * :: DeveloperApi ::
 * Compute gradient and loss for a Least-squared loss function, as used in linear regression.
 * This is correct for the averaged least squares loss function (mean squared error)
 *              L = 1/n ||A weights-y||^2
 * See also the documentation for the precise formulation.
 */
@DeveloperApi
class LeastSquaresGradient extends Gradient {
  override def compute(data: Vector, label: Double, weights: Vector): (Vector, Double) = {
    val brzData = data.toBreeze
    val brzWeights = weights.toBreeze
    val diff = brzWeights.dot(brzData) - label
    val loss = diff * diff
    val gradient = brzData * (2.0 * diff)

    (Vectors.fromBreeze(gradient), loss)
  }

  override def compute(
      data: Vector,
      label: Double,
      weights: Vector,
      cumGradient: Vector): Double = {
    val brzData = data.toBreeze
    val brzWeights = weights.toBreeze
    val diff = brzWeights.dot(brzData) - label

    brzAxpy(2.0 * diff, brzData, cumGradient.toBreeze)

    diff * diff
  }
}

/**
 * :: DeveloperApi ::
 * Compute gradient and loss for a Hinge loss function, as used in SVM binary classification.
 * See also the documentation for the precise formulation.
 * NOTE: This assumes that the labels are {0,1}
 */
@DeveloperApi
class HingeGradient extends Gradient {
  override def compute(data: Vector, label: Double, weights: Vector): (Vector, Double) = {
    val brzData = data.toBreeze
    val brzWeights = weights.toBreeze
    val dotProduct = brzWeights.dot(brzData)

    // Our loss function with {0, 1} labels is max(0, 1 - (2y – 1) (f_w(x)))
    // Therefore the gradient is -(2y - 1)*x
    val labelScaled = 2 * label - 1.0

    if (1.0 > labelScaled * dotProduct) {
      (Vectors.fromBreeze(brzData * (-labelScaled)), 1.0 - labelScaled * dotProduct)
    } else {
      (Vectors.dense(new Array[Double](weights.size)), 0.0)
    }
  }

  override def compute(
      data: Vector,
      label: Double,
      weights: Vector,
      cumGradient: Vector): Double = {
    val brzData = data.toBreeze
    val brzWeights = weights.toBreeze
    val dotProduct = brzWeights.dot(brzData)

    // Our loss function with {0, 1} labels is max(0, 1 - (2y – 1) (f_w(x)))
    // Therefore the gradient is -(2y - 1)*x
    val labelScaled = 2 * label - 1.0

    if (1.0 > labelScaled * dotProduct) {
      brzAxpy(-labelScaled, brzData, cumGradient.toBreeze)
      1.0 - labelScaled * dotProduct
    } else {
      0.0
    }
  }
}
