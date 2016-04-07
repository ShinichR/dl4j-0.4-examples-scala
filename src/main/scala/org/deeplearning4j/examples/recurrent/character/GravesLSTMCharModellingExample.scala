package org.deeplearning4j.examples.recurrent.character

import java.io.{File, IOException}
import java.net.URL
import java.nio.charset.Charset
import java.util.Random

import org.apache.commons.io.FileUtils
import org.deeplearning4j.nn.api.{Layer, OptimizationAlgorithm}
import org.deeplearning4j.nn.conf.distribution.UniformDistribution
import org.deeplearning4j.nn.conf.layers.{GravesLSTM, RnnOutputLayer}
import org.deeplearning4j.nn.conf.{BackpropType, MultiLayerConfiguration, NeuralNetConfiguration, Updater}
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork
import org.deeplearning4j.nn.weights.WeightInit
import org.deeplearning4j.optimize.listeners.ScoreIterationListener
import org.nd4j.linalg.api.ndarray.INDArray
import org.nd4j.linalg.dataset.DataSet
import org.nd4j.linalg.factory.Nd4j
import org.nd4j.linalg.lossfunctions.LossFunctions.LossFunction

/**GravesLSTM Character modelling example
 * @author Alex Black

   Example: Train a LSTM RNN to generates text, one character at a time.
  This example is somewhat inspired by Andrej Karpathy's blog post,
  "The Unreasonable Effectiveness of Recurrent Neural Networks"
  http://karpathy.github.io/2015/05/21/rnn-effectiveness/

  One minor difference between this example and Karpathy's work:
  The LSTM architectures appear to differ somewhat. GravesLSTM has peephole connections that
  Karpathy's char-rnn implementation appears to lack. See GravesLSTM javadoc for details.
  There are pros and cons to both architectures (addition of peephole connections is a more powerful
  model but has more parameters per unit), though they are not radically different in practice.

 	This example is set up to train on the Complete Works of William Shakespeare, downloaded
  from Project Gutenberg. Training on other text sources should be relatively easy to implement.

   For more details on RNNs in DL4J, see the following:
   http://deeplearning4j.org/usingrnns
   http://deeplearning4j.org/lstm
   http://deeplearning4j.org/recurrentnetwork
 */
object GravesLSTMCharModellingExample {
  def main(args: Array[String]) = {
    val lstmLayerSize = 200          //Number of units in each GravesLSTM layer
    val miniBatchSize = 32            //Size of mini batch to use when  training
    val exampleLength = 1000					//Length of each training example sequence to use. This could certainly be increased
    val tbpttLength = 50                       //Length for truncated backpropagation through time. i.e., do parameter updates ever 50 characters
    val numEpochs = 1							//Total number of training epochs
    val generateSamplesEveryNMinibatches = 10  //How frequently to generate samples from the network? 1000 characters / 50 tbptt length: 20 parameter updates per minibatch
    val nSamplesToGenerate = 4          //Number of samples to generate after each training epoch
    val nCharactersToSample = 300        //Length of each sample to generate
    val generationInitialization: String = null    //Optional character initialization; a random character is used if null
    // Above is Used to 'prime' the LSTM with a character sequence to continue/complete.
    // Initialization characters must all be in CharacterIterator.getMinimalCharacterSet() by default
    val rng = new Random(12345)

    //Get a DataSetIterator that handles vectorization of text into something we can use to train
    // our GravesLSTM network.
    val iter: CharacterIterator = getShakespeareIterator(miniBatchSize,exampleLength)
    val nOut: Int = iter.totalOutcomes()

    //Set up network configuration:
    val conf: MultiLayerConfiguration = new NeuralNetConfiguration.Builder()
      .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).iterations(1)
      .learningRate(0.1)
      .rmsDecay(0.95)
      .seed(12345)
      .regularization(true)
      .l2(0.001)
        .weightInit(WeightInit.XAVIER)
        .updater(Updater.RMSPROP)
      .list(3)
      .layer(0, new GravesLSTM.Builder().nIn(iter.inputColumns()).nOut(lstmLayerSize)
          .activation("tanh").build())
      .layer(1, new GravesLSTM.Builder().nIn(lstmLayerSize).nOut(lstmLayerSize)
          .activation("tanh").build())
      .layer(2, new RnnOutputLayer.Builder(LossFunction.MCXENT).activation("softmax")    //MCXENT + softmax for classification
          .nIn(lstmLayerSize).nOut(nOut).build())
       .backpropType(BackpropType.TruncatedBPTT).tBPTTForwardLength(tbpttLength).tBPTTBackwardLength(tbpttLength)
      .pretrain(false).backprop(true)
      .build()

    val net = new MultiLayerNetwork(conf)
    net.init()
    net.setListeners(new ScoreIterationListener(1))

    //Print the  number of parameters in the network (and for each layer)
    val layers: Array[Layer] = net.getLayers
    val totalNumParams = layers.zipWithIndex.map({ case (layer, i) =>
      val nParams: Int = layer.numParams()
      println("Number of parameters in layer " + i + ": " + nParams)
      nParams
    }).sum
    println("Total number of network parameters: " + totalNumParams)

    var miniBatchNumber = 0
    //Do training, and then generate and print samples from network
    (0 until numEpochs).foreach { i =>
        while(iter.hasNext()){
            val ds = iter.next()
            net.fit(ds);
            miniBatchNumber += 1
            if(miniBatchNumber % generateSamplesEveryNMinibatches == 0){
                println("--------------------")
                println("Completed " + miniBatchNumber + " minibatches of size " + miniBatchSize + "x" + exampleLength + " characters" )
                println("Sampling characters from network given initialization \"" + (if (generationInitialization == null) "" else generationInitialization) + "\"")
                val samples = sampleCharactersFromNetwork(generationInitialization,net,iter,rng,nCharactersToSample,nSamplesToGenerate)
                samples.indices.foreach { j =>
                    println("----- Sample " + j + " -----")
                    println(samples(j))
                    println()
                }
            }
        }
        iter.reset()  //Reset iterator for another epoch
    }

    println("\n\nExample complete")
  }

  /** Downloads Shakespeare training data and stores it locally (temp directory). Then set up and return a simple
   * DataSetIterator that does vectorization based on the text.
   * @param miniBatchSize Number of text segments in each training mini-batch
   * @param sequenceLength Number of characters in each text segment.
   */
  private def getShakespeareIterator(miniBatchSize: Int, sequenceLength: Int): CharacterIterator = {
    //The Complete Works of William Shakespeare
    //5.3MB file in UTF-8 Encoding, ~5.4 million characters
    //https://www.gutenberg.org/ebooks/100
    val url = "https://s3.amazonaws.com/dl4j-distribution/pg100.txt"
    val tempDir = System.getProperty("java.io.tmpdir")
    val fileLocation = tempDir + "/Shakespeare.txt"  //Storage location from downloaded file
    val f = new File(fileLocation)
    if( !f.exists() ){
      FileUtils.copyURLToFile(new URL(url), f)
      println("File downloaded to " + f.getAbsolutePath)
    } else {
      println("Using existing text file at " + f.getAbsolutePath)
    }

    if (!f.exists()) throw new IOException("File does not exist: " + fileLocation)  //Download problem?

    val validCharacters: Array[Char] = CharacterIterator.getMinimalCharacterSet  //Which characters are allowed? Others will be removed
    new CharacterIterator(fileLocation, Charset.forName("UTF-8"),
        miniBatchSize, sequenceLength, validCharacters, new Random(12345))
  }

  /** Generate a sample from the network, given an (optional, possibly null) initialization. Initialization
   * can be used to 'prime' the RNN with a sequence you want to extend/continue.<br>
   * Note that the initalization is used for all samples
   * @param initialization String, may be null. If null, select a random character as initialization for all samples
   * @param charactersToSample Number of characters to sample from network (excluding initialization)
   * @param net MultiLayerNetwork with one or more GravesLSTM/RNN layers and a softmax output layer
   * @param iter CharacterIterator. Used for going from indexes back to characters
   */
  private def sampleCharactersFromNetwork(initialization: String, net: MultiLayerNetwork, iter: CharacterIterator, rng: Random, charactersToSample: Int, numSamples: Int): Array[String] = {
    //Set up initialization. If no initialization: use a random character
    val initStr: String = if (initialization == null) {
      String.valueOf(iter.getRandomCharacter)
    } else {
      initialization
    }

    //Create input for initialization
    val initializationInput: INDArray = Nd4j.zeros(numSamples, iter.inputColumns(), initStr.length())
    val init: Array[Char] = initStr.toCharArray
    init.indices.foreach { i =>
      val idx = iter.convertCharacterToIndex(initStr(i))
      (0 until numSamples).foreach { j =>
        initializationInput.putScalar(Array[Int](j, idx, i), 1.0f)
      }
    }

    val sb = Array.fill(numSamples)(new StringBuilder(initialization))

    //Sample from network (and feed samples back into input) one character at a time (for all samples)
    //Sampling is done in parallel here
    net.rnnClearPreviousState()
    var output: INDArray = net.rnnTimeStep(initializationInput)
    output = output.tensorAlongDimension(output.size(2)-1, 1, 0)  //Gets the last time step output

    (0 until charactersToSample).foreach { i =>
      //Set up next input (single time step) by sampling from previous output
      val nextInput: INDArray  = Nd4j.zeros(numSamples,iter.inputColumns())
      //Output is a probability distribution. Sample from this for each example we want to generate, and add it to the new input
      (0 until numSamples).foreach { s =>
        val outputProbDistribution = (0 until iter.totalOutcomes).map(output.getDouble(s, _)).toArray
        val sampledCharacterIdx: Int = sampleFromDistribution(outputProbDistribution, rng)

        nextInput.putScalar(Array[Int](s, sampledCharacterIdx), 1.0f)    //Prepare next time step input
        sb(s).append(iter.convertIndexToCharacter(sampledCharacterIdx))  //Add sampled character to StringBuilder (human readable output)
      }
      output = net.rnnTimeStep(nextInput)  //Do one time step of forward pass
    }
    sb.map(_.toString)
  }

  /** Given a probability distribution over discrete classes, sample from the distribution
   * and return the generated class index.
   * @param distribution Probability distribution over classes. Must sum to 1.0
   */
  private def sampleFromDistribution(distribution: Array[Double], rng: Random) = {
    val d = rng.nextDouble()
    val accum = distribution.scanLeft(0.0)(_ + _).tail
    val exceeds = accum.zipWithIndex.filter(_._1 >= d)

    if (exceeds.length <= 0) {
      //Should never happen if distribution is a valid probability distribution
      throw new IllegalArgumentException(s"Distribution is invalid? d=$d, accum=$accum")
    }

    exceeds.head._2
  }
}
