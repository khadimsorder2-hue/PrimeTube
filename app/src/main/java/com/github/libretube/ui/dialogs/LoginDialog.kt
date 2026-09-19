package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.api.obj.Login
import com.github.libretube.api.obj.Token
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.DialogLoginBinding
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.preferences.InstanceSettings.Companion.INSTANCE_DIALOG_REQUEST_KEY
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class LoginDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogLoginBinding.inflate(layoutInflater)

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.login)
            .setPositiveButton(R.string.login, null)
            .setNegativeButton(R.string.register, null)
            // PrimeTube: built-in sign-in instructions
            .setNeutralButton(R.string.help, null)
            .setView(binding.root)
            .show()
            .apply {
                getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    val email = binding.username.text?.toString()
                    val password = binding.password.text?.toString()

                    if (!email.isNullOrEmpty() && !password.isNullOrEmpty()) {
                        signIn(email, password)
                    } else {
                        Toast.makeText(context, R.string.empty, Toast.LENGTH_SHORT).show()
                    }
                }
                getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                    val email = binding.username.text?.toString().orEmpty()
                    val password = binding.password.text?.toString().orEmpty()

                    if (isEmail(email)) {
                        showPrivacyAlertDialog(email, password)
                    } else if (email.isNotEmpty() && password.isNotEmpty()) {
                        signIn(email, password, true)
                    } else {
                        Toast.makeText(context, R.string.empty, Toast.LENGTH_SHORT).show()
                    }
                }
                getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                    showInstructions()
                }
            }
    }

    /**
     * PrimeTube: step-by-step sign-in instructions (English + Bangla).
     * Most "login issues" are actually instance problems: the account belongs to a
     * specific instance and many instances are currently blocked by YouTube.
     */
    private fun showInstructions() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.prime_login_help_title)
            .setMessage(R.string.prime_login_help_msg)
            .setPositiveButton(R.string.okay, null)
            .show()
    }

    private fun signIn(username: String, password: String, createNewAccount: Boolean = false) {
        val login = Login(username, password)
        lifecycleScope.launch(Dispatchers.IO) {
            val response = try {
                if (createNewAccount) {
                    RetrofitInstance.authApi.register(login)
                } else {
                    RetrofitInstance.authApi.login(login)
                }
            } catch (e: HttpException) {
                val serverError = e.response()?.errorBody()?.string()?.runCatching {
                    JsonHelper.json.decodeFromString<Token>(this).error
                }?.getOrNull()

                // PrimeTube: map raw server errors to messages the user can act on
                val errorMessage = when {
                    serverError == null -> context?.getString(R.string.server_error).orEmpty()
                    serverError.contains("bot", ignoreCase = true) ||
                        serverError.contains("confirm", ignoreCase = true) ->
                        context?.getString(R.string.prime_login_err_blocked).orEmpty()
                    e.code() == 401 || e.code() == 403 ||
                        serverError.contains("wrong", ignoreCase = true) ||
                        serverError.contains("password", ignoreCase = true) ||
                        serverError.contains("username", ignoreCase = true) ->
                        context?.getString(R.string.prime_login_err_credentials).orEmpty()
                    else -> serverError
                }
                context?.toastFromMainDispatcher(errorMessage)
                return@launch
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                // PrimeTube: network failures get a hint to try another instance
                val errorMessage = when (e) {
                    is UnknownHostException, is ConnectException,
                    is SocketTimeoutException, is SSLException ->
                        context?.getString(R.string.prime_login_err_network).orEmpty()
                    else -> e.localizedMessage.orEmpty()
                }
                context?.toastFromMainDispatcher(errorMessage)
                return@launch
            }

            if (response.error != null) {
                context?.toastFromMainDispatcher(response.error)
                return@launch
            }
            if (response.token == null) {
                // PrimeTube: never fail silently
                context?.toastFromMainDispatcher(R.string.server_error)
                return@launch
            }

            context?.toastFromMainDispatcher(
                if (createNewAccount) R.string.registered else R.string.loggedIn
            )

            PreferenceHelper.setToken(response.token)
            PreferenceHelper.setUsername(login.username)

            withContext(Dispatchers.Main) {
                setFragmentResult(
                    INSTANCE_DIALOG_REQUEST_KEY,
                    bundleOf(IntentData.loginTask to true)
                )
            }
            dialog?.dismiss()
        }
    }

    private fun showPrivacyAlertDialog(email: String, password: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.privacy_alert)
            .setMessage(R.string.username_email)
            .setNegativeButton(R.string.proceed) { _, _ ->
                signIn(email, password, true)
            }
            .setPositiveButton(R.string.cancel, null)
            .show()
    }

    private fun isEmail(text: String): Boolean {
        return Patterns.EMAIL_ADDRESS.toRegex().matches(text)
    }
}
