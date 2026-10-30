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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
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

        val state = when {
            !ok -> DawnView.State.Offline
            serviceActive -> DawnView.State.Ready
            else -> DawnView.State.Stopped
        }
        binding.lykDawn.setState(state)
        binding.lykStatusTitle.text = when (state) {
            DawnView.State.Offline -> getString(R.string.lyk_status_not_connected)
            DawnView.State.Ready -> getString(R.string.lyk_status_ready)
            DawnView.State.Stopped -> getString(R.string.lyk_status_stopped)
        }
        binding.lykStatusSubtitle.text = when (state) {
            DawnView.State.Offline -> getString(R.string.lyk_status_not_connected_sub)
            DawnView.State.Ready -> getString(R.string.lyk_status_ready_sub, gatewaySettings.deviceName ?: defaultName(), serverHost())
            DawnView.State.Stopped -> getString(R.string.lyk_status_stopped_sub)
        }
        styleServiceButton(serviceActive)
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

    private fun styleServiceButton(running: Boolean) {
        val b = binding.lykButtonService
        val lime = ContextCompat.getColor(requireContext(), R.color.lyk_lime)
        val ink = ContextCompat.getColor(requireContext(), R.color.lyk_ink)
        val text = ContextCompat.getColor(requireContext(), R.color.lyk_text)
        val line = ContextCompat.getColor(requireContext(), R.color.lyk_line)
        if (running) {
            b.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            b.setTextColor(text); b.strokeColor = ColorStateList.valueOf(line); b.strokeWidth = 2
        } else {
            b.backgroundTintList = ColorStateList.valueOf(lime)
            b.setTextColor(ink); b.strokeWidth = 0
        }
    }

    private fun renderSims() {
        val sims = SubscriptionsHelper.getActiveSimCards(requireContext()).sortedBy { it.slotIndex }
        val tiles = binding.lykSimTiles
        tiles.removeAllViews()
        binding.lykSimAuto.isVisible = false

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

        // A single SIM is always the one that sends: store it so nothing depends on phone defaults.
        if (sims.size == 1 && (messagesSettings.simSelectionMode != MessagesSettings.SimSelectionMode.Fixed ||
                    messagesSettings.defaultSimSlot != sims.first().slotIndex)
        ) {
            messagesSettings.import(mapOf("sim_selection_mode" to "Fixed", "default_sim_slot" to sims.first().slotIndex))
        }

        val fixed = messagesSettings.simSelectionMode == MessagesSettings.SimSelectionMode.Fixed
        val selectedSlot = if (fixed) messagesSettings.defaultSimSlot else null
        val lime = ContextCompat.getColor(requireContext(), R.color.lyk_lime)
        val line = ContextCompat.getColor(requireContext(), R.color.lyk_line)
        val hi = ContextCompat.getColor(requireContext(), R.color.lyk_surface_hi)
        val surface = ContextCompat.getColor(requireContext(), R.color.lyk_surface)

        sims.forEachIndexed { index, s ->
            val tile = layoutInflater.inflate(R.layout.lyk_item_sim, tiles, false) as MaterialCardView
            tile.findViewById<TextView>(R.id.lykSimNumeral).text = s.simNumber.toString()
            tile.findViewById<TextView>(R.id.lykSimCarrier).text = s.carrierName ?: getString(R.string.lyk_sim_option, s.simNumber, "")
            tile.findViewById<TextView>(R.id.lykSimPhone).text = s.phoneNumber ?: "—"
            val chosen = selectedSlot == s.slotIndex
            tile.strokeColor = if (chosen) lime else line
            tile.strokeWidth = if (chosen) 4 else 2
            tile.setCardBackgroundColor(if (chosen) hi else surface)
            if (index == sims.lastIndex) (tile.layoutParams as ViewGroup.MarginLayoutParams).marginEnd = 0
            if (sims.size > 1) tile.setOnClickListener {
                messagesSettings.import(mapOf("sim_selection_mode" to "Fixed", "default_sim_slot" to s.slotIndex))
                renderSims()
            }
            tiles.addView(tile)
        }
        if (sims.size > 1) {
            binding.lykSimAuto.isVisible = true
            val auto = !fixed || selectedSlot == null
            binding.lykSimAuto.text = if (auto) getString(R.string.lyk_sim_auto_on) else getString(R.string.lyk_sim_auto)
            binding.lykSimAuto.setTextColor(ContextCompat.getColor(requireContext(), if (auto) R.color.lyk_lime else R.color.lyk_muted))
            binding.lykSimAuto.setOnClickListener {
                messagesSettings.import(mapOf("sim_selection_mode" to "OSDefault", "default_sim_slot" to null))
                renderSims()
            }
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
