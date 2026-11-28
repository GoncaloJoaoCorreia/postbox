package com.postbox.shared.domain.model

object CarrierMetadata {
    fun requiredFieldsFor(carrier: Carrier): List<MetadataField> = when (carrier) {
        Carrier.paack -> listOf(
            MetadataField(
                name = "postcode",
                label = "Delivery postcode",
                hint = "Postcode of the delivery address in its country format (e.g. 4250-004 in Portugal, 28001 in Spain)",
                validationRegex = "^[0-9A-Za-z][0-9A-Za-z -]{2,9}$"
            )
        )
        Carrier.postnl -> listOf(
            MetadataField(
                name = "postcode",
                label = "Delivery postcode",
                hint = "Postcode of the delivery address in its country format \u2014 NL 1234AB, PT 4250-004, BE 1234, LU L-1234",
                validationRegex = "^([1-9]\\d{3}[A-Z]{2}|\\d{4}-?\\d{3}|L-?\\d{4}|[1-9]\\d{3})$"
            )
        )
        Carrier.dpd -> listOf(
            MetadataField(
                name = "country",
                label = "Destination country",
                hint = "DPD subsidiary whose network the parcel is in: PT (default) or NL",
                validationRegex = "^(PT|NL)$"
            )
        )
        Carrier.dhl, Carrier.ctt, Carrier.ecoscooting -> emptyList()
    }
}
