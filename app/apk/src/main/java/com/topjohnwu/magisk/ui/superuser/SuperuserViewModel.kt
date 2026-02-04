package com.topjohnwu.magisk.ui.superuser

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.os.Process
import androidx.databinding.Bindable
import androidx.databinding.ObservableArrayList
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.arch.AsyncLoadViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.R
import com.topjohnwu.magisk.core.data.magiskdb.PolicyDao
import com.topjohnwu.magisk.core.ktx.getLabel
import com.topjohnwu.magisk.core.model.su.SuPolicy
import com.topjohnwu.magisk.core.utils.RootUtils
import com.topjohnwu.magisk.databinding.MergeObservableList
import com.topjohnwu.magisk.databinding.RvItem
import com.topjohnwu.magisk.databinding.bindExtra
import com.topjohnwu.magisk.databinding.diffList
import com.topjohnwu.magisk.databinding.set
import com.topjohnwu.magisk.dialog.SuperuserRevokeDialog
import com.topjohnwu.magisk.events.AuthEvent
import com.topjohnwu.magisk.events.SnackbarEvent
import com.topjohnwu.magisk.utils.asText
import com.topjohnwu.magisk.view.TextItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SuperuserViewModel(
    private val db: PolicyDao
) : AsyncLoadViewModel() {

    private val itemNoData = TextItem(R.string.superuser_policy_none)

    private val itemsHelpers = ObservableArrayList<TextItem>()
    private val itemsPolicies = diffList<PolicyRvItem>()

    val items = MergeObservableList<RvItem>()
        .insertList(itemsHelpers)
        .insertList(itemsPolicies)
    val extraBindings = bindExtra {
        it.put(BR.listener, this)
        it.put(BR.viewModel, this)
    }

    @get:Bindable
    var loading = true
        private set(value) = set(value, field, { field = it }, BR.loading)

    @SuppressLint("InlinedApi")
    override suspend fun doLoadWork() {
        if (!Info.showSuperUser) {
            return
        }

        loading = true

        // Fetch data in IO thread
        val policyItems = withContext(Dispatchers.IO) {
            db.deleteOutdated()
            db.delete(AppContext.applicationInfo.uid)

            // Fetch all authorization records from database
            val policyMap = db.fetchAll().associateBy { it.uid }
            val pm = AppContext.packageManager

            // Get all third-party apps via root service
            val packageNames = RootUtils.getInstalledPackages()
            val items = packageNames.asFlow()
                .filter { it != AppContext.packageName }
                .mapNotNull { packageName ->
                    try {
                        val info = pm.getPackageInfo(packageName, MATCH_UNINSTALLED_PACKAGES)
                        val applicationInfo = info.applicationInfo ?: return@mapNotNull null

                        // Get existing policy or create new one with QUERY status
                        val policy = policyMap[applicationInfo.uid] ?: SuPolicy(
                            uid = applicationInfo.uid,
                            policy = SuPolicy.QUERY
                        )

                        PolicyRvItem(
                            this@SuperuserViewModel, policy,
                            info.packageName,
                            info.sharedUserId != null,
                            applicationInfo.loadIcon(pm),
                            applicationInfo.getLabel(pm) ?: packageName
                        )
                    } catch (e: PackageManager.NameNotFoundException) {
                        null
                    }
                }.toCollection(ArrayList<PolicyRvItem>())

            // Add shell (UID 2000) if not already in list
            val shellUid = 2000
            if (items.none { it.item.uid == shellUid }) {
                val shellPolicy = policyMap[shellUid] ?: SuPolicy(
                    uid = shellUid,
                    policy = SuPolicy.QUERY
                )
                items.add(PolicyRvItem(
                    this@SuperuserViewModel, shellPolicy,
                    "com.android.shell",
                    false,
                    pm.defaultActivityIcon,
                    "Shell"
                ))
            }

            // Sort: ALLOW apps first, DENY/QUERY apps after, then by app name
            items.sortWith(compareBy(
                { it.item.policy == SuPolicy.QUERY },  // QUERY last
                { it.item.policy != SuPolicy.ALLOW },  // ALLOW first
                { it.appName.lowercase(Locale.ROOT) },
                { it.packageName }
            ))

            items
        }

        // Update list on main thread
        itemsPolicies.update(policyItems)
        loading = false
    }

    // ---

    fun deletePressed(item: PolicyRvItem) {
        fun updateState() = viewModelScope.launch {
            db.delete(item.item.uid)
            doLoadWork()
        }

        if (Config.suAuth) {
            AuthEvent { updateState() }.publish()
        } else {
            SuperuserRevokeDialog(item.title) { updateState() }.show()
        }
    }

    fun updateNotify(item: PolicyRvItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.update(item.item)
            }
            val res = when {
                item.item.notification -> R.string.su_snack_notif_on
                else -> R.string.su_snack_notif_off
            }
            SnackbarEvent(res.asText(item.appName)).publish()
        }
    }

    fun updateLogging(item: PolicyRvItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.update(item.item)
            }
            val res = when {
                item.item.logging -> R.string.su_snack_log_on
                else -> R.string.su_snack_log_off
            }
            SnackbarEvent(res.asText(item.appName)).publish()
        }
    }

    fun updatePolicy(item: PolicyRvItem, policy: Int) {
        fun updateState() {
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        if (policy >= SuPolicy.ALLOW) {
                            // Grant: update or create policy
                            item.item.policy = policy
                            db.update(item.item)
                        } else {
                            // Deny: delete the policy completely
                            db.delete(item.item.uid)
                        }
                    }
                    // Show snackbar on main thread
                    val res = if (policy >= SuPolicy.ALLOW) {
                        R.string.su_snack_grant.asText(item.appName)
                    } else {
                        R.string.su_snack_deny.asText(item.appName)
                    }
                    SnackbarEvent(res).publish()
                    // Reload to show updated list
                    doLoadWork()
                } catch (e: Exception) {
                    SnackbarEvent("Error: ${e.message}").publish()
                }
            }
        }

        if (Config.suAuth) {
            AuthEvent { updateState() }.publish()
        } else {
            updateState()
        }
    }
}
