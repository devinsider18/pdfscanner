package ua.com.devinsider.pdfscanner.data.repository

import android.app.Activity
import com.unity3d.mediation.*
import com.unity3d.mediation.rewarded.*
import com.ironsource.mediationsdk.model.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object AdsManager {
    private var rewardedAd: LevelPlayRewardedAd? = null
    private const val AD_UNIT_ID = "DefaultRewardedVideo"

    fun loadRewardedAd() {
        if (rewardedAd == null) {
            rewardedAd = LevelPlayRewardedAd(AD_UNIT_ID)
        }
        rewardedAd?.loadAd()
    }

    suspend fun showRewardedAd(activity: Activity): Boolean = suspendCancellableCoroutine { continuation ->
        val ad = rewardedAd
        if (ad == null || !ad.isAdReady()) {
            if (continuation.isActive) continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        var wasRewarded = false

        ad.setListener(object : LevelPlayRewardedAdListener {
            override fun onAdLoaded(adInfo: LevelPlayAdInfo) {}
            override fun onAdLoadFailed(error: LevelPlayAdError) {}
            override fun onAdDisplayed(adInfo: LevelPlayAdInfo) {}
            override fun onAdDisplayFailed(error: LevelPlayAdError, adInfo: LevelPlayAdInfo) {
                if (continuation.isActive) continuation.resume(false)
            }
            override fun onAdClicked(adInfo: LevelPlayAdInfo) {}
            override fun onAdClosed(adInfo: LevelPlayAdInfo) {
                if (continuation.isActive) {
                    continuation.resume(wasRewarded)
                }
            }
            // Use Any for LevelPlayReward to avoid unresolved reference if it's imported dynamically or we can just omit type if possible, but Kotlin needs type.
            override fun onAdRewarded(reward: LevelPlayReward, adInfo: LevelPlayAdInfo) {
                wasRewarded = true
                if (continuation.isActive) {
                    continuation.resume(true)
                }
            }
        })

        ad.showAd(activity)
    }
}
