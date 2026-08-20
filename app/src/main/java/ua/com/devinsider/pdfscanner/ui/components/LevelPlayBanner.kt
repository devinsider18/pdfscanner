package ua.com.devinsider.pdfscanner.ui.components

import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.unity3d.mediation.LevelPlayAdSize
import com.unity3d.mediation.LevelPlayAdInfo
import com.unity3d.mediation.LevelPlayAdError
import com.unity3d.mediation.banner.LevelPlayBannerAdView
import com.unity3d.mediation.banner.LevelPlayBannerAdViewListener

@Composable
fun LevelPlayBanner(
    bannerSize: LevelPlayAdSize = LevelPlayAdSize.BANNER,
    isAdsSdkReady: Boolean
) {
    if (!isAdsSdkReady) {
        return
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        factory = { context ->
            val bannerContainer = FrameLayout(context)
            bannerContainer.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )

            // IronSource App Key or AD Unit ID
            // We use a default AD unit ID if not specified, often it's "DefaultBanner"
            val banner = LevelPlayBannerAdView(context, "DefaultBanner")
            
            
            banner.setBannerListener(object : LevelPlayBannerAdViewListener {
                override fun onAdLoaded(adInfo: LevelPlayAdInfo) {}
                override fun onAdLoadFailed(error: LevelPlayAdError) {}
                override fun onAdDisplayed(adInfo: LevelPlayAdInfo) {}
                override fun onAdDisplayFailed(adInfo: LevelPlayAdInfo, error: LevelPlayAdError) {}
                override fun onAdClicked(adInfo: LevelPlayAdInfo) {}
                override fun onAdCollapsed(adInfo: LevelPlayAdInfo) {}
                override fun onAdExpanded(adInfo: LevelPlayAdInfo) {}
                override fun onAdLeftApplication(adInfo: LevelPlayAdInfo) {}
            })
            
            bannerContainer.addView(banner)
            banner.loadAd()
            
            bannerContainer
        },
        update = { bannerContainer ->
            // no-op for now
        }
    )
}
