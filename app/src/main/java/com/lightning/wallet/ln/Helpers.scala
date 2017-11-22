package com.lightning.wallet.ln

import fr.acinq.bitcoin._
import com.lightning.wallet.ln.wire._
import com.lightning.wallet.ln.Scripts._
import com.lightning.wallet.ln.crypto.ShaChain._
import com.lightning.wallet.ln.crypto.Generators._
import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar}
import scala.util.{Success, Try}


object Helpers { me =>
  def makeLocalTxs(commitTxNumber: Long, localParams: LocalParams,
                   remoteParams: AcceptChannel, commitmentInput: InputInfo,
                   localPerCommitmentPoint: Point, spec: CommitmentSpec) = {

    val localHtlcPubkey = derivePubKey(localParams.htlcBasepoint, localPerCommitmentPoint)
    val localDelayedPaymentPubkey = derivePubKey(localParams.delayedPaymentBasepoint, localPerCommitmentPoint)
    val localRevocationPubkey = revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
    val remotePaymentPubkey = derivePubKey(remoteParams.paymentBasepoint, localPerCommitmentPoint)
    val remoteHtlcPubkey = derivePubKey(remoteParams.htlcBasepoint, localPerCommitmentPoint)

    val commitTx = Scripts.makeCommitTx(commitmentInput, commitTxNumber, localParams.paymentBasepoint,
      remoteParams.paymentBasepoint, localParams.isFunder, LNParams.dustLimit, localRevocationPubkey,
      remoteParams.toSelfDelay, localDelayedPaymentPubkey, remotePaymentPubkey, localHtlcPubkey,
      remoteHtlcPubkey, spec)

    val htlcTimeoutTxs \ htlcSuccessTxs = Scripts.makeHtlcTxs(commitTx.tx, LNParams.dustLimit,
      localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey, localHtlcPubkey,
      remoteHtlcPubkey, spec)

    (commitTx, htlcTimeoutTxs, htlcSuccessTxs)
  }

  def makeRemoteTxs(commitTxNumber: Long, localParams: LocalParams,
                    remoteParams: AcceptChannel, commitmentInput: InputInfo,
                    remotePerCommitmentPoint: Point, spec: CommitmentSpec) = {

    val localHtlcPubkey = derivePubKey(localParams.htlcBasepoint, remotePerCommitmentPoint)
    val localPaymentPubkey = derivePubKey(localParams.paymentBasepoint, remotePerCommitmentPoint)
    val remoteRevocationPubkey = revocationPubKey(localParams.revocationBasepoint, remotePerCommitmentPoint)
    val remoteDelayedPaymentPubkey = derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
    val remoteHtlcPubkey = derivePubKey(remoteParams.htlcBasepoint, remotePerCommitmentPoint)

    val commitTx = Scripts.makeCommitTx(commitmentInput, commitTxNumber, remoteParams.paymentBasepoint,
      localParams.paymentBasepoint, !localParams.isFunder, remoteParams.dustLimitSat, remoteRevocationPubkey,
      localParams.toSelfDelay, remoteDelayedPaymentPubkey, localPaymentPubkey, remoteHtlcPubkey,
      localHtlcPubkey, spec)

    val htlcTimeoutTxs \ htlcSuccessTxs = Scripts.makeHtlcTxs(commitTx.tx, remoteParams.dustLimitSat,
      remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, remoteHtlcPubkey,
      localHtlcPubkey, spec)

    (commitTx, htlcTimeoutTxs, htlcSuccessTxs,
      remoteHtlcPubkey, remoteRevocationPubkey)
  }

  object Closing {
    type SuccessAndClaim = (HtlcSuccessTx, ClaimDelayedOutputTx)
    type TimeoutAndClaim = (HtlcTimeoutTx, ClaimDelayedOutputTx)

    def isValidFinalScriptPubkey(raw: BinaryData) = Try(Script parse raw) match {
      case Success(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pkh, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) => pkh.data.size == 20
      case Success(OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil) => scriptHash.data.size == 20
      case Success(OP_0 :: OP_PUSHDATA(pubkeyHash, _) :: Nil) if pubkeyHash.length == 20 => true
      case Success(OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil) if scriptHash.length == 32 => true
      case _ => false
    }

    def makeFirstClosing(commitments: Commitments, localScriptPubkey: BinaryData,
                         remoteScriptPubkey: BinaryData, rate: Long) = {

      // This is just to estimate the weight, it depends on size of the pubkey scripts
      val closingWeight: Int = Transaction.weight(Scripts.addSigs(makeFunderClosingTx(commitments.commitInput,
        localScriptPubkey, remoteScriptPubkey, dustLimit = Satoshi(0), closingFee = Satoshi(0), commitments.localCommit.spec),
        commitments.localParams.fundingPrivKey.publicKey, commitments.remoteParams.fundingPubkey, "aa" * 71, "bb" * 71).tx)

      val closingFee = Scripts.weight2fee(feeratePerKw = rate, closingWeight)
      makeClosing(commitments, localScriptPubkey, remoteScriptPubkey, closingFee)
    }

    def makeClosing(commitments: Commitments, localScriptPubkey: BinaryData,
                    remoteScriptPubkey: BinaryData, closingFee: Satoshi) = {

      require(isValidFinalScriptPubkey(localScriptPubkey), "Invalid localScriptPubkey")
      require(isValidFinalScriptPubkey(remoteScriptPubkey), "Invalid remoteScriptPubkey")

      val dustLimitSat = math.max(LNParams.dustLimit.amount, commitments.remoteParams.dustLimitSatoshis)
      val closingTx = makeFunderClosingTx(commitments.commitInput, localScriptPubkey, remoteScriptPubkey,
        Satoshi(dustLimitSat), closingFee, commitments.localCommit.spec)

      val localClosingSig = Scripts.sign(closingTx, commitments.localParams.fundingPrivKey)
      val closingSigned = ClosingSigned(commitments.channelId, closingFee.amount, localClosingSig)
      (closingTx, closingSigned)
    }

    def makeFunderClosingTx(commitTxInput: InputInfo, localScriptPubKey: BinaryData, remoteScriptPubKey: BinaryData,
                            dustLimit: Satoshi, closingFee: Satoshi, spec: CommitmentSpec): ClosingTx = {

      require(spec.htlcs.isEmpty, "No HTLCs allowed")
      val toRemoteAmount: Satoshi = MilliSatoshi(spec.toRemoteMsat)
      val toLocalAmount: Satoshi = MilliSatoshi(spec.toLocalMsat) - closingFee
      val toLocalOutput = if (toLocalAmount < dustLimit) Nil else TxOut(toLocalAmount, localScriptPubKey) :: Nil
      val toRemoteOutput = if (toRemoteAmount < dustLimit) Nil else TxOut(toRemoteAmount, remoteScriptPubKey) :: Nil
      val input = TxIn(commitTxInput.outPoint, Array.emptyByteArray, sequence = 0xffffffffL) :: Nil
      val tx = Transaction(version = 2, input, toLocalOutput ++ toRemoteOutput, lockTime = 0)
      ClosingTx(commitTxInput, LexicographicalOrdering sort tx)
    }

    def nextClosingFee(localClosingFee: Satoshi, remoteClosingFee: Satoshi): Satoshi =
      (localClosingFee + remoteClosingFee) / 4 * 2

    def claimCurrentLocalCommitTxOutputs(commitments: Commitments, bag: PaymentInfoBag) = {
      val localPerCommitmentPoint = perCommitPoint(commitments.localParams.shaSeed, commitments.localCommit.index.toInt)
      val localRevocationPubkey = revocationPubKey(commitments.remoteParams.revocationBasepoint, localPerCommitmentPoint)
      val localDelayedPrivkey = derivePrivKey(commitments.localParams.delayedPaymentKey, localPerCommitmentPoint)

      def makeClaimDelayedOutput(tx: Transaction): ClaimDelayedOutputTx = {
        val claimDelayed = Scripts.makeClaimDelayedOutputTx(tx, localRevocationPubkey, commitments.localParams.toSelfDelay,
          localDelayedPrivkey.publicKey, commitments.localParams.defaultFinalScriptPubKey, commitments.localCommit.spec.feeratePerKw)

        val sig = Scripts.sign(claimDelayed, localDelayedPrivkey)
        Scripts.addSigs(claimDelayed, sig)
      }

      val allSuccessTxs = for {
        HtlcTxAndSigs(info: HtlcSuccessTx, localSig, remoteSig) <- commitments.localCommit.htlcTxsAndSigs
        PaymentInfo(_, 1, preimage, _, _, _, _) <- bag.getPaymentInfo(info.add.paymentHash).toOption
        success: HtlcSuccessTx = Scripts.addSigs(info, localSig, remoteSig, preimage)
        delayedClaim <- Scripts checkSpendable makeClaimDelayedOutput(success.tx)
      } yield success -> delayedClaim

      val allTimeoutTxs = for {
        HtlcTxAndSigs(info: HtlcTimeoutTx, localSig, remoteSig) <- commitments.localCommit.htlcTxsAndSigs
        timeout: HtlcTimeoutTx = Scripts.addSigs(htlcTimeoutTx = info, localSig, remoteSig)
        delayedClaim <- Scripts checkSpendable makeClaimDelayedOutput(timeout.tx)
      } yield timeout -> delayedClaim

      val claimMainDelayedTx = Scripts.checkSpendable {
        // When local commit is spent our main output is also delayed
        makeClaimDelayedOutput(commitments.localCommit.commitTx.tx)
      }

      LocalCommitPublished(claimMainDelayedTx.toList, allSuccessTxs,
        allTimeoutTxs, commitTx = commitments.localCommit.commitTx.tx)
    }

    def claimRemoteCommitTxOutputs(commitments: Commitments, remoteCommit: RemoteCommit, bag: PaymentInfoBag) = {
      val localPaymentPrivkey = derivePrivKey(commitments.localParams.paymentKey, remoteCommit.remotePerCommitmentPoint)
      val localHtlcPrivkey = derivePrivKey(commitments.localParams.htlcKey, remoteCommit.remotePerCommitmentPoint)

      val (remoteCommitTx, timeoutTxs, successTxs, remoteHtlcPubkey, remoteRevocationPubkey) =
        makeRemoteTxs(remoteCommit.index, commitments.localParams, commitments.remoteParams,
          commitments.commitInput, remoteCommit.remotePerCommitmentPoint, remoteCommit.spec)

      val claimSuccessTxs = for {
        HtlcTimeoutTx(_, _, add) <- timeoutTxs
        PaymentInfo(_, 1, preimage, _, _, _, _) <- bag.getPaymentInfo(add.paymentHash).toOption
        claimHtlcSuccessTx = Scripts.makeClaimHtlcSuccessTx(remoteCommitTx.tx, localHtlcPrivkey.publicKey,
          remoteHtlcPubkey, remoteRevocationPubkey, commitments.localParams.defaultFinalScriptPubKey,
          add, remoteCommit.spec.feeratePerKw)

        sig = Scripts.sign(claimHtlcSuccessTx, localHtlcPrivkey)
        signed = Scripts.addSigs(claimHtlcSuccessTx, sig, preimage)
        claimSuccess <- Scripts checkSpendable signed
      } yield claimSuccess

      val claimTimeoutTxs = for {
        HtlcSuccessTx(_, _, add) <- successTxs
        claimHtlcTimeoutTx = Scripts.makeClaimHtlcTimeoutTx(remoteCommitTx.tx, localHtlcPrivkey.publicKey,
          remoteHtlcPubkey, remoteRevocationPubkey, commitments.localParams.defaultFinalScriptPubKey,
          add, remoteCommit.spec.feeratePerKw)

        sig = Scripts.sign(claimHtlcTimeoutTx, localHtlcPrivkey)
        signed = Scripts.addSigs(claimHtlcTimeoutTx, sig)
        claimTimeout <- Scripts checkSpendable signed
      } yield claimTimeout

      RemoteCommitPublished(Scripts.checkSpendable {
        val txWithInputInfo = Scripts.makeClaimP2WPKHOutputTx(remoteCommitTx.tx, localPaymentPrivkey.publicKey,
          commitments.localParams.defaultFinalScriptPubKey, remoteCommit.spec.feeratePerKw)

        val sig = Scripts.sign(txWithInputInfo, localPaymentPrivkey)
        Scripts.addSigs(txWithInputInfo, localPaymentPrivkey.publicKey, sig)
      }.toList, claimSuccessTxs.toList, claimTimeoutTxs.toList, remoteCommitTx.tx)
    }

    // Special case when we have lost our data and ask them to spend their local current commit tx
    def claimRemoteLostCommitTxOutputs(commitments: Commitments, remoteCommit: RemoteCommit, commitTx: Transaction) = {
      val localPaymentPrivkey = derivePrivKey(commitments.localParams.paymentKey, remoteCommit.remotePerCommitmentPoint)

      RemoteCommitPublished(Scripts.checkSpendable {
        val txWithInputInfo = Scripts.makeClaimP2WPKHOutputTx(commitTx, localPaymentPrivkey.publicKey,
          commitments.localParams.defaultFinalScriptPubKey, LNParams.broadcaster.feeRatePerKw)

        val sig = Scripts.sign(txWithInputInfo, localPaymentPrivkey)
        Scripts.addSigs(txWithInputInfo, localPaymentPrivkey.publicKey, sig)
      }.toList, claimHtlcSuccess = Nil, claimHtlcTimeout = Nil, commitTx)
    }

    def claimRevokedRemoteCommitTxOutputs(commitments: Commitments, tx: Transaction) = {
      val txNumber = Scripts.obscuredCommitTxNumber(number = Scripts.decodeTxNumber(tx.txIn.head.sequence, tx.lockTime),
        !commitments.localParams.isFunder, commitments.remoteParams.paymentBasepoint, commitments.localParams.paymentBasepoint)

      val index = moves(largestTxIndex - txNumber)
      val hashes = commitments.remotePerCommitmentSecrets.hashes

      getHash(hashes)(index) map { remotePerCommitmentSecret =>
        val remotePerCommitmentSecretScalar = Scalar(remotePerCommitmentSecret)
        val remotePerCommitmentPoint = remotePerCommitmentSecretScalar.toPoint

        val remoteDelayedPaymentPubkey = derivePubKey(commitments.remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
        val remoteRevocationPrivkey = revocationPrivKey(commitments.localParams.revocationSecret, remotePerCommitmentSecretScalar)
        val localPrivkey = derivePrivKey(commitments.localParams.paymentKey, remotePerCommitmentPoint)

        val claimMainTx = Scripts checkSpendable {
          val claimMain = Scripts.makeClaimP2WPKHOutputTx(tx, localPrivkey.publicKey,
            commitments.localParams.defaultFinalScriptPubKey, LNParams.broadcaster.feeRatePerKw)

          val sig = Scripts.sign(claimMain, localPrivkey)
          Scripts.addSigs(claimMain, localPrivkey.publicKey, sig)
        }

        val claimPenaltyTx = Scripts checkSpendable {
          val txinfo = Scripts.makeMainPenaltyTx(tx, remoteRevocationPrivkey.publicKey,
            commitments.localParams.defaultFinalScriptPubKey, commitments.remoteParams.toSelfDelay,
            remoteDelayedPaymentPubkey, LNParams.broadcaster.feeRatePerKw)

          val sig = Scripts.sign(txinfo, remoteRevocationPrivkey)
          Scripts.addSigs(txinfo, sig)
        }

        RevokedCommitPublished(claimMainTx.toList,
          claimPenaltyTx.toList, commitTx = tx)
      }
    }
  }

  object Funding {
    def makeFundingInputInfo(fundingTxHash: BinaryData, fundingTxOutputIndex: Int,
                             fundingSatoshis: Satoshi, fundingPubkey1: PublicKey,
                             fundingPubkey2: PublicKey): InputInfo = {

      val multisig = Scripts.multiSig2of2(fundingPubkey1, fundingPubkey2)
      val fundingTxOut = TxOut(fundingSatoshis, Script pay2wsh multisig)
      val outPoint = OutPoint(fundingTxHash, fundingTxOutputIndex)
      InputInfo(outPoint, fundingTxOut, Script write multisig)
    }

    // Assuming we are always a funder
    def makeFirstFunderCommitTxs(cmd: CMDOpenChannel, remoteParams: AcceptChannel,
                                 fundingTxHash: BinaryData, fundingTxOutputIndex: Int,
                                 remoteFirstPoint: Point) = {

      val toLocalMsat = cmd.fundingAmountSat * 1000L - cmd.pushMsat
      val commitmentInput: InputInfo = makeFundingInputInfo(fundingTxHash, fundingTxOutputIndex,
        Satoshi(cmd.fundingAmountSat), cmd.localParams.fundingPrivKey.publicKey, remoteParams.fundingPubkey)

      val localPerCommitmentPoint = perCommitPoint(cmd.localParams.shaSeed, 0L)
      val localSpec = CommitmentSpec(Set.empty, Set.empty, Set.empty, cmd.initialFeeratePerKw, toLocalMsat, cmd.pushMsat)
      val remoteSpec = CommitmentSpec(Set.empty, Set.empty, Set.empty, cmd.initialFeeratePerKw, cmd.pushMsat, toLocalMsat)
      val (localCommitTx, _, _) = makeLocalTxs(0L, cmd.localParams, remoteParams, commitmentInput, localPerCommitmentPoint, localSpec)
      val (remoteCommitTx, _, _, _, _) = makeRemoteTxs(0L, cmd.localParams, remoteParams, commitmentInput, remoteFirstPoint, remoteSpec)
      (localSpec, localCommitTx, remoteSpec, remoteCommitTx)
    }
  }
}