package africa.lyk.gateway.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.aventrix.jnanoid.jnanoid.NanoIdUtils
import kotlinx.coroutines.launch
import me.capcom.smsgateway.R
import me.capcom.smsgateway.databinding.LykFragmentHomeBinding
import me.capcom.smsgateway.domain.EntitySource
import me.capcom.smsgateway.domain.MessageContent
import me.capcom.smsgateway.helpers.SettingsHelper
import me.capcom.smsgateway.helpers.SubscriptionsHelper
import me.capcom.smsgateway.modules.events.EventBus
import me.capcom.smsgateway.modules.gateway.GatewayService
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.gateway.events.DeviceRegisteredEvent
import me.capcom.smsgateway.modules.localserver.LocalServerSettings
import me.capcom.smsgateway.modules.messages.MessagesService
import me.capcom.smsgateway.modules.messages.MessagesSettings
import me.capcom.smsgateway.modules.messages.data.Message
import me.capcom.smsgateway.modules.messages.data.SendParams
import me.capcom.smsgateway.modules.messages.data.SendRequest
import me.capcom.smsgateway.modules.orchestrator.OrchestratorService
import org.koin.android.ext.android.inject
import java.util.Date

/**
 * [lyk] One screen: connect once, see which SIMs the phone has and pick the one that sends, send a test.
 * Everything else the upstream home screen offered sits under "Advanced".
 */
class LykHomeFragment : Fragment() {

    private var _binding: LykFragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val gatewaySettings: GatewaySettings by inject()
    private val localServerSettings: LocalServerSettings by inject()
    private val messagesSettings: MessagesSettings by inject()
    private val settingsHelper: SettingsHelper by inject()
    private val gatewaySvc: GatewayService by inject()
    private val messagesSvc: MessagesService by inject()
    private val orchestratorSvc: OrchestratorService by inject()
    private val events: EventBus by inject()

    private var serviceActive = false

    private val permissionsRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (!result.values.all { it }) {
            Toast.makeText(requireContext(), R.string.lyk_sim_permission, Toast.LENGTH_SHORT).show()
        }
        orchestratorSvc.start(requireContext().applicationContext, false)
        render()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = LykFragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.lykInputName.setText(gatewaySettings.deviceName ?: defaultName())
        binding.lykServerLine.text = getString(R.string.lyk_server_line, serverHost())

        binding.lykButtonConnect.setOnClickListener { connect() }
        binding.lykButtonService.setOnClickListener { toggleService() }
        binding.lykButtonTest.setOnClickListener { sendTest() }
        binding.lykButtonAdvanced.setOnClickListener {
            val show = !binding.lykAdvanced.isVisible
            binding.lykAdvanced.isVisible = show
            binding.lykButtonAdvanced.setText(if (show) R.string.lyk_advanced_hide else R.string.lyk_advanced_show)
        }
        binding.lykSwitchAutostart.isChecked = settingsHelper.autostart
        binding.lykSwitchAutostart.setOnCheckedChangeListener { _, on -> settingsHelper.autostart = on }
        binding.lykSwitchLocal.isChecked = localServerSettings.enabled
        binding.lykSwitchLocal.setOnCheckedChangeListener { _, on -> localServerSettings.enabled = on }
        binding.lykButtonDisconnect.setOnClickListener { confirmDisconnect() }

        gatewaySvc.isActiveLiveData(requireContext()).observe(viewLifecycleOwner) {
            serviceActive = it
            render()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            events.collect<DeviceRegisteredEvent.Success> { render() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            events.collect<DeviceRegisteredEvent.Failure> { event ->
                Toast.makeText(
                    requireContext(), getString(R.string.lyk_connect_failed, event.reason), Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun connected() = gatewaySettings.registrationInfo != null

    private fun serverHost(): String = Regex("^https?://([^/]+)").find(serverUrl())?.groupValues?.get(1) ?: serverUrl()

    private fun serverUrl(): String =
        gatewaySettings.serverUrl.takeIf { it != GatewaySettings.PUBLIC_URL }
            ?: getString(R.string.lyk_default_server_url)

    private fun defaultName() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private fun render() {
        if (_binding == null) return
        val ok = connected()
        binding.lykCardConnect.isVisible = !ok

        val (title, sub, color) = when {
            !ok -> Triple(getString(R.string.lyk_status_not_connected), getString(R.string.lyk_status_not_connected_sub), 0xFF9E9E9E.toInt())
            serviceActive -> Triple(
                getString(R.string.lyk_status_ready),
                getString(R.string.lyk_status_ready_sub, gatewaySettings.deviceName ?: defaultName(), serverHost()),
                0xFF2E9E5B.toInt()
            )
            else -> Triple(getString(R.string.lyk_status_stopped), getString(R.string.lyk_status_stopped_sub), 0xFFE0A100.toInt())
        }
        binding.lykStatusTitle.text = title
        binding.lykStatusSubtitle.text = sub
        binding.lykStatusDot.backgroundTintList = ColorStateList.valueOf(color)
        binding.lykButtonService.isVisible = ok
        binding.lykButtonService.setText(if (serviceActive) R.string.lyk_stop else R.string.lyk_start)
        binding.lykButtonTest.isEnabled = ok && serviceActive
        binding.lykButtonDisconnect.isVisible = ok
        binding.lykDeviceId.text = gatewaySettings.deviceId?.let { getString(R.string.lyk_device_id, it) } ?: ""
        binding.lykDeviceId.setOnClickListener {
            gatewaySettings.deviceId?.let {
                (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("device id", it))
            }
        }
        renderSims()
    }

    private fun renderSims() {
        val sims = SubscriptionsHelper.getActiveSimCards(requireContext()).sortedBy { it.slotIndex }
        val group = binding.lykSimGroup
        group.setOnCheckedChangeListener(null)
        group.removeAllViews()

        val hasPhonePermission = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        binding.lykSimCount.text = when {
            !hasPhonePermission -> getString(R.string.lyk_sim_permission)
            sims.isEmpty() -> getString(R.string.lyk_sim_none)
            sims.size == 1 -> getString(R.string.lyk_sim_count_one)
            else -> getString(R.string.lyk_sim_count_many, sims.size)
        }
        if (sims.isEmpty()) return

        val fixed = messagesSettings.simSelectionMode == MessagesSettings.SimSelectionMode.Fixed
        val selectedSlot = if (fixed) messagesSettings.defaultSimSlot else null

        fun option(id: Int, text: String, checked: Boolean) = RadioButton(requireContext()).apply {
            this.id = id
            this.text = text
            this.isChecked = checked
            group.addView(this, RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT))
        }

        sims.forEach { s ->
            val label = listOfNotNull(s.carrierName, s.phoneNumber).joinToString(" · ").ifEmpty { "—" }
            option(SIM_ID_BASE + s.slotIndex, getString(R.string.lyk_sim_option, s.simNumber, label), selectedSlot == s.slotIndex)
        }
        if (sims.size > 1) option(AUTO_ID, getString(R.string.lyk_sim_auto), !fixed || selectedSlot == null)
        else group.check(SIM_ID_BASE + sims.first().slotIndex)

        group.setOnCheckedChangeListener { _, checkedId ->
            val settings = if (checkedId >= SIM_ID_BASE)
                mapOf("sim_selection_mode" to "Fixed", "default_sim_slot" to (checkedId - SIM_ID_BASE))
            else
                mapOf("sim_selection_mode" to "OSDefault", "default_sim_slot" to null)
            messagesSettings.import(settings)
        }
        // A single SIM is always the one that sends: store it so nothing depends on phone defaults.
        if (sims.size == 1 && selectedSlot != sims.first().slotIndex) {
            messagesSettings.import(mapOf("sim_selection_mode" to "Fixed", "default_sim_slot" to sims.first().slotIndex))
        }
    }

    private fun connect() {
        val code = binding.lykInputCode.text?.toString()?.trim().orEmpty()
        if (code.isEmpty()) {
            binding.lykInputCode.error = getString(R.string.lyk_connect_missing)
            return
        }
        gatewaySettings.deviceName = binding.lykInputName.text?.toString()?.trim()
        try {
            gatewaySettings.import(mapOf("cloud_url" to getString(R.string.lyk_default_server_url), "private_token" to code))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.lyk_connect_failed, e.message), Toast.LENGTH_LONG).show()
            return
        }
        gatewaySettings.enabled = true
        localServerSettings.enabled = false
        binding.lykSwitchLocal.isChecked = false
        binding.lykInputCode.setText("")
        requestPermissionsAndStart()
    }

    private fun toggleService() {
        if (serviceActive) orchestratorSvc.stop(requireContext()) else requestPermissionsAndStart()
    }

    private fun requestPermissionsAndStart() {
        val needed = listOfNotNull(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.READ_PHONE_NUMBERS.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU },
        ).filter { ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            orchestratorSvc.start(requireContext().applicationContext, false)
        } else {
            permissionsRequest.launch(needed.toTypedArray())
        }
    }

    private fun sendTest() {
        val number = binding.lykInputTestNumber.text?.toString()?.trim().orEmpty()
        if (!Regex("^\\+[0-9]{8,15}$").matches(number)) {
            binding.lykInputTestNumber.error = getString(R.string.lyk_test_bad_number)
            return
        }
        messagesSvc.enqueueMessage(
            SendRequest(
                EntitySource.Local,
                Message(
                    NanoIdUtils.randomNanoId(),
                    content = MessageContent.Text(getString(R.string.lyk_test_text, gatewaySettings.deviceName ?: defaultName())),
                    phoneNumbers = listOf(number),
                    isEncrypted = false,
                    createdAt = Date(),
                ),
                SendParams(true, false, null, null, null, null),
            )
        )
        Toast.makeText(requireContext(), R.string.lyk_test_queued, Toast.LENGTH_SHORT).show()
    }

    private fun confirmDisconnect() {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.lyk_disconnect_confirm)
            .setPositiveButton(R.string.lyk_disconnect) { _, _ ->
                orchestratorSvc.stop(requireContext())
                gatewaySettings.enabled = false
                gatewaySettings.registrationInfo = null
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val SIM_ID_BASE = 1000
        private const val AUTO_ID = 999
        fun newInstance() = LykHomeFragment()
    }
}
