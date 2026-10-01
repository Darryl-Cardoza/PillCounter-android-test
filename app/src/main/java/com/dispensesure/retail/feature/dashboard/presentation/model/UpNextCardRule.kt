package com.dispensesure.retail.feature.dashboard.presentation.model

import com.dispensesure.retail.feature.dashboard.domain.model.QueueItem

// The first queue row becomes the big "UP NEXT" card only when it is a dispense;
// inventory batches have no drug to show, so they stay normal rows.
internal fun upNextDispense(items: List<QueueItem>): QueueItem.Dispense? =
    items.firstOrNull() as? QueueItem.Dispense
