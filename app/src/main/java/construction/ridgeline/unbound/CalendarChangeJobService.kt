package construction.ridgeline.unbound

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * Fires whenever the device calendar store changes (event added/edited/removed
 * anywhere), refreshes all Unbound widgets, and re-arms itself — trigger-content
 * jobs are one-shot by design.
 */
class CalendarChangeJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        UnboundWidgetProvider.updateAll(applicationContext)
        UnboundWidgetProvider.scheduleCalendarChangeJob(applicationContext)
        return false // work done synchronously
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        UnboundWidgetProvider.scheduleCalendarChangeJob(applicationContext)
        return false
    }
}
