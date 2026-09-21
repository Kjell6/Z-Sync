package de.kjell.zencompanion.review

import android.app.Activity
import com.google.android.play.core.review.ReviewManagerFactory

/** Play In-App Review overlay. No-ops when Play Store is missing or quota is spent. */
object PlayInAppReview {
    fun request(activity: Activity) {
        runCatching {
            val manager = ReviewManagerFactory.create(activity)
            manager.requestReviewFlow().addOnCompleteListener { task ->
                if (!task.isSuccessful) return@addOnCompleteListener
                runCatching { manager.launchReviewFlow(activity, task.result) }
            }
        }
    }
}
