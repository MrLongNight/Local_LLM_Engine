package com.example.ui.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.EngineViewModel
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.GdnPolicy
import com.mydocanizer.llm.engine.ModelInfo
import com.mydocanizer.llm.engine.VulkanMatvecWorkgroup
@Composable
internal fun VulkanTuningSection(
    config: EngineConfig,
    currentModel: ModelInfo?,
    viewModel: EngineViewModel
) {
                        if (config.backendPreference != BackendPreference.CPU) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Vulkan-DMMV-Workgroup (Diagnose)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "AUTO nutzt für Q4_K auf Mali den gemessenen Basis-MMV-Pfad; SUBGROUP und LARGE erzwingen nur die MatVec-Workgroup-Größe. Die Q4_K/Q5_K/Q6_K- und Prefill-Overrides bleiben interne A/B-Profile.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            VulkanMatvecWorkgroup.entries.filter { it.nativeId in 0..2 }.forEach { workgroup ->
                                FilterChip(
                                    selected = config.vulkanMatvecWorkgroup == workgroup,
                                    onClick = {
                                        viewModel.updateConfig(config.copy(vulkanMatvecWorkgroup = workgroup))
                                    },
                                    label = {
                                        Text(
                                            when (workgroup) {
                                                VulkanMatvecWorkgroup.AUTO -> "AUTO – Upstream"
                                                VulkanMatvecWorkgroup.SUBGROUP -> "SUBGROUP – Geräte-Subgroup (Mali-G57: 16)"
                                                VulkanMatvecWorkgroup.LARGE -> "LARGE – 4× Subgroup"
                                                VulkanMatvecWorkgroup.Q4K_BASE_DMMV -> "Q4_K Basis-DMMV (A/B, intern)"
                                                VulkanMatvecWorkgroup.Q5K_BASE_DMMV -> "Q5_K Basis-DMMV (A/B, intern)"
                                                VulkanMatvecWorkgroup.Q6K_BASE_DMMV -> "Q6_K Basis-DMMV (A/B, intern)"
                                                VulkanMatvecWorkgroup.Q4K_PREFILL_F16 -> "Q4_K Prefill F16/F32 (A/B, intern)"
                                                VulkanMatvecWorkgroup.MMVQ_FORCE -> "MMVQ Integer-Dot (A/B, intern)"
                                            }
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .testTag("vulkan_matvec_workgroup_${workgroup.name}")
                                )
                            }
                        }
    
                        Divider(modifier = Modifier.padding(vertical = 12.dp))
                        Text(
                            text = "Gated Delta Net (GDN)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "ARCH_DEFAULT verwendet für Qwen3.5 die unfused Referenzpfade und lässt andere Architekturen bei den Upstream-Defaults. Die übrigen Modi sind für die CPU/Vulkan-Korrektheitsmatrix und werden beim nächsten Modellladen aktiv.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        GdnPolicy.entries.forEach { policy ->
                            FilterChip(
                                selected = config.gdnPolicy == policy,
                                onClick = { viewModel.updateConfig(config.copy(gdnPolicy = policy)) },
                                label = { Text(policy.label) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .testTag("gdn_policy_${policy.name.lowercase()}")
                            )
                        }
    
                        val activeModel = currentModel
                        if (activeModel != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Aktives Backend: ${activeModel.activeBackend}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
}
