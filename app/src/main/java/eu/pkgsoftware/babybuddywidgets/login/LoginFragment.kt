package eu.pkgsoftware.babybuddywidgets.login

import android.annotation.SuppressLint
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.navigation.Navigation.findNavController
import com.squareup.phrase.Phrase
import eu.pkgsoftware.babybuddywidgets.BaseFragment
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.databinding.LoginFragmentBinding
import eu.pkgsoftware.babybuddywidgets.tutorial.Trackable
import eu.pkgsoftware.babybuddywidgets.tutorial.TutorialEntry
import eu.pkgsoftware.babybuddywidgets.tutorial.TutorialManagement
import eu.pkgsoftware.babybuddywidgets.utils.AsyncPromise
import eu.pkgsoftware.babybuddywidgets.utils.AsyncPromiseFailure
import eu.pkgsoftware.babybuddywidgets.utils.CancelParallel
import eu.pkgsoftware.babybuddywidgets.utils.Promise
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.URL

class LoginFragment : BaseFragment() {
    private lateinit var binding: LoginFragmentBinding
    private lateinit var loginButton: Button
    private lateinit var addressEdit: EditText
    private lateinit var loginNameEdit: EditText
    private lateinit var loginPasswordEdit: EditText

    private val menu = LoggedOutMenu(this)
    private val cancelParallelLogin = CancelParallel()

    private fun updateLoginButton() {
        loginButton.isEnabled =
            addressEdit.text.length > 0 && loginNameEdit.text.length > 0 && loginPasswordEdit.text.length > 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = LoginFragmentBinding.inflate(inflater, container, false)
        val view: View = binding.root
        loginButton = view.findViewById(R.id.loginButton)
        addressEdit = view.findViewById(R.id.serverAddressEdit)
        loginNameEdit = view.findViewById(R.id.loginNameEdit)
        loginPasswordEdit = view.findViewById(R.id.passwordEdit)
        val tw: TextWatcher = object : TextWatcher {
            override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
            override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
            override fun afterTextChanged(editable: Editable) {
                updateLoginButton()
            }
        }
        val credStore = mainActivity.credStore
        var serverUrl = credStore.serverUrl
        if (serverUrl == "") {
            if (isTestlab) {
                serverUrl = "https://babybuddy-test.pkgsoftware.eu/"
            }
        }
        addressEdit.setText(serverUrl)
        addressEdit.addTextChangedListener(tw)
        loginNameEdit.addTextChangedListener(tw)
        loginPasswordEdit.addTextChangedListener(tw)
        loginButton.setOnClickListener(View.OnClickListener { view1: View? -> uiStartLogin() })
        binding.passwordEdit.setText("")
        binding.loginNameEdit.setText("")
        binding.passwordEdit.setOnEditorActionListener { v: TextView?, actionId: Int, event: KeyEvent? ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                uiStartLogin()
                return@setOnEditorActionListener true
            }
            false
        }
        binding.loginInfoText.movementMethod = LinkMovementMethod.getInstance()
        binding.qrCode.setOnClickListener { view1: View? ->
            val controller = findNavController(requireView())
            controller.navigate(R.id.action_LoginFragment_to_QRCodeLoginFragment)
        }
        binding.qrCode.isEnabled = false
        updateLoginButton()
        return binding.root
    }

    private inner class BurgerMenuTutorialEntry(_trackable: Trackable) : TutorialEntry(
        "tutorial_arrow_menu",
        this@LoginFragment.javaClass,
        getString(R.string.tutorial_suggest_visit_help),
        _trackable,
    ) {
        override val fullId = "help_hint" // Stupid legacy name...
        override val maxPresentations = 2
    }

    override fun setupTutorialMessages(m: TutorialManagement) {
        val trackable = object : Trackable() {
            override val position: PointF? get() {
                val mainAct = mainActivity
                val toolbar = mainAct.findViewById<View>(R.id.app_toolbar)
                val r = Rect()
                toolbar.getGlobalVisibleRect(r)

                return PointF((r.right - dpToPx(20f)).toFloat(), (r.bottom - dpToPx(10f)).toFloat())
            }
        }
        m.addItem(BurgerMenuTutorialEntry(trackable))
    }

    private fun uiStartLogin() {
        hideKeyboard()
        Utils(mainActivity).httpCleaner(
            addressEdit.text.toString(),
            object : Promise<String, Any> {
                override fun succeeded(s: String) {
                    addressEdit!!.setText(s)
                    performLogin()
                }

                override fun failed(o: Any) {}
            }
        )
    }

    @get:SuppressLint("SetTextI18n")
    private val isTestlab: Boolean
        /**
         * Check if running in a testlab-context
         */
        private get() {
            var testlab = false
            val cr = mainActivity.contentResolver
            if (cr != null) {
                val testLabSetting = Settings.System.getString(cr, "firebase.test.lab")
                if ("true" == testLabSetting) {
                    testlab = true
                }
            }
            return testlab
        }

    private fun showProgress() {
        showProgress(getString(R.string.logging_in_message))
    }

    override fun onResume() {
        super.onResume()
        mainActivity.setTitle(getString(R.string.first_fragment_label))
        mainActivity.addMenuProvider(menu)
        val b = arguments
        if (b != null) {
            if (b.getBoolean("noCameraAccess", false)) {
                mainActivity.binding.globalErrorBubble.flashMessage(
                    R.string.login_qrcode_camera_access_was_disabled_message
                )
            }
        }
        if (mainActivity.credStore.appToken != "") {
            progressDialog.hide()
            moveToLoggedIn()
        } else {
            mainActivity.storage.deleteAllLoginData()

            val qrCode = QRCode(this, null, true)
            qrCode.cameraOnInitialized = Runnable {
                binding.qrCode.isEnabled = qrCode.hasCamera
                if (qrCode.hasCamera) {
                    binding.qrCodeInfoText.setText(R.string.login_qrcode_info_text)
                } else {
                    binding.qrCodeInfoText.setText(R.string.login_qrcode_info_text_no_camera)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        mainActivity.removeMenuProvider(menu)
        mainActivity.invalidateOptionsMenu()
    }

    private suspend fun grabToken(asyncGrabber: AsyncGrabAppToken): String {
        asyncGrabber.login(loginNameEdit.text.toString(), loginPasswordEdit.text.toString())
        var result: String? = null
        result = asyncGrabber.fromProfilePage()
        if (result == null) {
            result = asyncGrabber.parseFromSettingsPage()
        }
        if (result == null) {
            throw java.lang.Exception(getString(R.string.login_error_token_not_found))
        } else {
            return result
        }
    }

    private fun translateLoginErrorMessage(msg: String?): String? {
        return when (msg) {
            "Baby Buddy login/ response not 200" -> getString(R.string.login_error_server_response)
            "csrftoken missing" -> getString(R.string.login_error_csrf_missing)
            "Invalid username or password (403)",
            "Invalid username or password (sessionid)" -> getString(R.string.login_error_invalid_credentials)
            "Login failed for unknown reason (server issue?)" -> getString(R.string.login_error_unknown)
            "Cannot access profile page" -> getString(R.string.login_error_profile_access)
            "Cannot access user settings" -> getString(R.string.login_error_settings_access)
            "Cannot find api-key section" -> getString(R.string.login_error_apikey_not_found)
            else -> msg
        }
    }

    private fun performLogin() {
        mainActivity.scope.launch {
            showProgress()
            cancelParallelLogin.cancelParallel {
                val credStore = mainActivity.credStore
                credStore.storeServerUrl(addressEdit.text.toString())
                var token: String? = null
                token = try {
                    grabToken(AsyncGrabAppToken(URL(addressEdit.text.toString())))
                } catch (e: IOException) {
                    showError(true, getString(R.string.login_failed_title), translateLoginErrorMessage(e.message))
                    return@cancelParallel
                } catch (e: Exception) {
                    e.printStackTrace()
                    val internalMsg = Phrase.from(resources, R.string.login_internal_error_message)
                        .put("error", e.message ?: "")
                        .format().toString()
                    showError(true, getString(R.string.login_failed_title), internalMsg)
                    return@cancelParallel
                }
                credStore.storeAppToken(token)

                try {
                    AsyncPromise.call<Any, String> {
                        Utils(mainActivity).testLoginToken(it)
                    }
                } catch (e: AsyncPromiseFailure) {
                    mainActivity.logout()
                    showError(true, getString(R.string.login_failed_title), e.value.toString())
                    return@cancelParallel
                }
                moveToLoggedIn()
            }
        }.invokeOnCompletion {
            progressDialog.hide()
        }
    }

    private fun moveToLoggedIn() {
        val controller = findNavController(requireView())
        controller.navigate(R.id.action_LoginFragment_to_loggedInFragment2)
    }
}