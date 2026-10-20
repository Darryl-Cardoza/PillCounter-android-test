package com.dispensesure.retail.core.region

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dispensesure.retail.R

// US string → its Canada wording. Strings that read the same in both regions aren't listed.
private val canadaWording: Map<Int, Int> = mapOf(
    R.string.queue_tab_controlled to R.string.queue_tab_controlled_ca,
    R.string.queue_tab_hazardous to R.string.queue_tab_hazardous_ca,
    R.string.kpi_cont_drugs to R.string.kpi_cont_drugs_ca,
    R.string.kpi_hazardous to R.string.kpi_hazardous_ca,
    R.string.tour_desc_kpi_dispense to R.string.tour_desc_kpi_dispense_ca,
    R.string.tour_desc_scan_container to R.string.tour_desc_scan_container_ca,
    R.string.tour_desc_inv_camera_preview to R.string.tour_desc_inv_camera_preview_ca,
    R.string.tour_desc_inv_edit_actions to R.string.tour_desc_inv_edit_actions_ca,
    R.string.tour_desc_inv_end_count to R.string.tour_desc_inv_end_count_ca,
    R.string.ndc_value to R.string.ndc_value_ca,
    R.string.ndc to R.string.ndc_ca,
    R.string.ndc_gtin14 to R.string.ndc_gtin14_ca,
    R.string.ndc_number to R.string.ndc_number_ca,
    R.string.the_scanned_ndc_does_not_match to R.string.the_scanned_ndc_does_not_match_ca,
    R.string.scan_ndc_toast to R.string.scan_ndc_toast_ca,
    R.string.rescan_ndc_does_not_match_toast to R.string.rescan_ndc_does_not_match_toast_ca,
    R.string.ndcs_label to R.string.ndcs_label_ca,
    R.string.total_ndc_count to R.string.total_ndc_count_ca,
    R.string.pdf_ndc_label to R.string.pdf_ndc_label_ca,
    R.string.batch_stock_count_scanned_drug_details to R.string.batch_stock_count_scanned_drug_details_ca,
    R.string.batch_stock_count_label_ndc to R.string.batch_stock_count_label_ndc_ca,
    R.string.batch_stock_count_total_ndcs to R.string.batch_stock_count_total_ndcs_ca,
    R.string.batch_stock_count_drug_not_found to R.string.batch_stock_count_drug_not_found_ca,
    R.string.batch_stock_count_ndc_not_in_request to R.string.batch_stock_count_ndc_not_in_request_ca,
    R.string.photo_label_requested_ndc to R.string.photo_label_requested_ndc_ca,
    R.string.photo_label_dispensed_ndc to R.string.photo_label_dispensed_ndc_ca,
    R.string.hl7_notification_drug_not_found_api_failed to R.string.hl7_notification_drug_not_found_api_failed_ca,
    R.string.hl7_notification_drug_not_found_no_drug to R.string.hl7_notification_drug_not_found_no_drug_ca,
    R.string.hl7_notification_edit_rx_drug_not_found to R.string.hl7_notification_edit_rx_drug_not_found_ca,
    R.string.hl7_notification_new_rx_ndc_missing to R.string.hl7_notification_new_rx_ndc_missing_ca,
    R.string.hl7_notification_refill_ndc_or_qty_missing to R.string.hl7_notification_refill_ndc_or_qty_missing_ca,
    R.string.setting_group_hazardous to R.string.setting_group_hazardous_ca,
    R.string.setting_hazardous_drug to R.string.setting_hazardous_drug_ca,
    R.string.tray_classification_message to R.string.tray_classification_message_ca,
    R.string.hazardous_tray_warning to R.string.hazardous_tray_warning_ca,
    R.string.non_hazardous_tray_warning to R.string.non_hazardous_tray_warning_ca,
    R.string.clear_tray_color_lists_confirm to R.string.clear_tray_color_lists_confirm_ca,
)

/** The string to show in this region: the Canada wording when there is one, else the same id. */
@StringRes
fun Region.wording(@StringRes usStringId: Int): Int = when (this) {
    Region.US -> usStringId
    Region.CA -> canadaWording[usStringId] ?: usStringId
}

/** [stringResource] in the logged-in region's wording. */
@Composable
fun regionStringResource(@StringRes usStringId: Int, vararg formatArgs: Any): String =
    stringResource(LocalRegion.current.wording(usStringId), *formatArgs)
