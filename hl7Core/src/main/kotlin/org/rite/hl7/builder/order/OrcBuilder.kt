package org.rite.hl7.builder.order

import org.rite.hl7.domain.model.OrderData
import org.rite.hl7.domain.utils.HL7Utils
import org.rite.hl7.domain.utils.HL7Utils.buildComponent

fun buildORC(order: OrderData): String {

    // ORC-2: Placer Order Number (ID ^ namespace)
    val placerOrder = buildComponent(
        order.placerOrderId,
        order.placerOrderNamespace ?: ""
    )

    // ORC-3: Filler Order Number (ID ^ namespace)
    val fillerOrder = buildComponent(
        order.fillerOrderId ?: "",
        order.fillerOrderNamespace ?: ""
    )

    // ORC-12: Ordering Provider (ID ^ family ^ given)
    val orderingProvider = buildComponent(
        order.orderingProviderId ?: "",
        order.orderingProviderFamilyName ?: "",
        order.orderingProviderGivenName ?: ""
    )

    /** Assemble ORC segment with HL7-defined field positions **/
    return HL7Utils.buildSegment(
        "ORC",  /** Segment ID **/

        /** ORC-1: Order control **/
        order.orderControl,

        /** ORC-2: Placer order number **/
        placerOrder,

        /** ORC-3: Filler order number **/
        fillerOrder,

        /** ORC-4: Placer group number (not used) **/
        "",

        /** ORC-5: Order status **/
        order.orderStatus ?: "",

        /** ORC-6: Response flag (not used) **/
        "",

        /** ORC-7: Quantity/timing (not used) **/
        "",

        /** ORC-8: Parent (not used) **/
        "",

        /** ORC-9: Date/time of transaction **/
        order.orderDateTime ?: "",

        /** ORC-10: Transaction date/time (not used) **/
        "",

        /** ORC-11: Entered by (not used) **/
        "",

        /** ORC-12: Ordering provider **/
        orderingProvider,

        /** ORC-13: Enterer’s location (not used) **/
        "",

        /** ORC-14: Call back phone number (not used) **/
        "",

        /** ORC-15: Order effective date/time (not used) **/
        "",

        /** ORC-16: Order control code reason (not used) **/
        "",

        /** ORC-17: Entering organization (not used) **/
        "",

        /** ORC-18: Entering device (not used) **/
        "",

        /** ORC-19: Action by (not used) **/
        "",

        /** ORC-20: Advanced beneficiary notice code (not used) **/
        "",

        /** ORC-21: Ordering facility **/
        order.orderingFacility ?: ""
    )
}
