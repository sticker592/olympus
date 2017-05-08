package com.lightning.wallet.ln

import fr.acinq.bitcoin._
import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.bitcoin.Crypto.{PrivateKey, sha256}


object LNParams {
  var extendedPrivateKey: ExtendedPrivateKey = _
  var extendedCloudPrivateKey: ExtendedPrivateKey = _

  def setup(seed: BinaryData): Unit = generate(seed) match {
    case master =>
      extendedPrivateKey = derivePrivateKey(master, hardened(46) :: hardened(0) :: Nil)
      extendedCloudPrivateKey = derivePrivateKey(master, hardened(92) :: hardened(0) :: Nil)
  }

  val updateFeeMinDiffRatio = 0.25 // Must update
  val maxChannelCapacity = MilliSatoshi(16777216000L)
  val maxHtlcValue = MilliSatoshi(4294967295L)
  val maxReserveToFundingRatio = 0.05 // %
  val reserveToFundingRatio = 0.01 // %
  val untilExpiryBlocks = 6
  val minDepth = 2

  def deriveParamsPrivateKey(index: Long, n: Long): PrivateKey =
    derivePrivateKey(extendedPrivateKey, index :: n :: Nil).privateKey

  def exceedsReserve(channelReserveSatoshis: Long, fundingSatoshis: Long): Boolean =
    channelReserveSatoshis.toDouble / fundingSatoshis > maxReserveToFundingRatio

  def shouldUpdateFee(commitmentFeeratePerKw: Long, networkFeeratePerKw: Long): Boolean = {
    val feeRatio = (networkFeeratePerKw - commitmentFeeratePerKw) / commitmentFeeratePerKw.toDouble
    networkFeeratePerKw > 0 && Math.abs(feeRatio) > updateFeeMinDiffRatio
  }

  def makeLocalParams(fundingSat: Long, finalScriptPubKey: BinaryData, keyIndex: Long) =
    LocalParams(Block.TestnetGenesisBlock.blockId, dustLimitSatoshis = 542, maxHtlcValueInFlightMsat = Long.MaxValue,
      channelReserveSatoshis = (reserveToFundingRatio * fundingSat).toLong, htlcMinimumMsat = 500, toSelfDelay = 144, maxAcceptedHtlcs = 10,
      fundingPrivKey = deriveParamsPrivateKey(keyIndex, 0L), revocationSecret = deriveParamsPrivateKey(keyIndex, 1L), paymentKey = deriveParamsPrivateKey(keyIndex, 2L),
      delayedPaymentKey = deriveParamsPrivateKey(keyIndex, 3L), defaultFinalScriptPubKey = finalScriptPubKey, shaSeed = sha256(deriveParamsPrivateKey(keyIndex, 4L).toBin),
      isFunder = true, globalFeatures = "", localFeatures = "01")
}