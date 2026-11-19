package eu.pkgsoftware.babybuddywidgets.login

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import androidx.navigation.Navigation.findNavController
import eu.pkgsoftware.babybuddywidgets.BaseFragment
import eu.pkgsoftware.babybuddywidgets.LoggedInFragment
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.backup.BackupCenterBottomSheet
import eu.pkgsoftware.babybuddywidgets.children.ChildManagerBottomSheet
import eu.pkgsoftware.babybuddywidgets.children.ChildProfileDialog
import eu.pkgsoftware.babybuddywidgets.debugging.GlobalDebugObject
import eu.pkgsoftware.babybuddywidgets.tags.TagManagerDialog

class LoggedInMenu(val fragment: BaseFragment) : MenuProvider {
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.loggedin_menu, menu)
        if (!GlobalDebugObject.ENABLED) {
            menu.findItem(R.id.exportDebugLogsMenuItem)?.isVisible = false
        }
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        val view = fragment.view ?: return false
        val activity = fragment.mainActivity
        val navController = findNavController(view)

        return when (menuItem.itemId) {
            R.id.manageBabiesMenuItem -> {
                val children = activity.repository.listChildren()
                val currentSlug = activity.storage.login<String>("selected-child-slug")
                if (children.isEmpty()) {
                    ChildProfileDialog.show(activity) { created ->
                        (fragment as? LoggedInFragment)?.selectChild(created)
                    }
                } else {
                    ChildManagerBottomSheet.show(activity, currentSlug) { chosen ->
                        (fragment as? LoggedInFragment)?.selectChild(chosen)
                    }
                }
                true
            }
            R.id.manageTagsMenuItem -> {
                TagManagerDialog.showManager(activity)
                true
            }
            R.id.backupCenterMenuItem -> {
                BackupCenterBottomSheet.show(activity)
                true
            }
            R.id.settingsMenuMenuItem -> {
                navController.navigate(R.id.action_global_settingsFragment)
                true
            }
            R.id.showHelpMenuMenuItem -> {
                navController.navigate(R.id.global_showHelp)
                true
            }
            R.id.aboutPageMenuItem -> {
                navController.navigate(R.id.global_aboutFragment)
                true
            }
            R.id.exportDebugLogsMenuItem -> {
                navController.navigate(R.id.action_global_debugLogDisplay)
                true
            }
            R.id.contactDeveloperMenuItem -> {
                navController.navigate(R.id.action_global_contactDeveloperFragment)
                true
            }
            else -> false
        }
    }
}
