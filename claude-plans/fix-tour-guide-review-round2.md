# Tour guide review fixes, round 2

1. PillScanningViewModelCaptureTest: captureVialForTour returns (no timeout) when notes setting is off. No prod change.
2. DashboardScreen: extract `internal fun shouldShowTourOffer(pending, locked)`; use at the offer dialog; unit test in DashboardTourOfferTest.
3. PR #713 body: remove the handleRdeDispenseRequest line.
4. VerifyRxTourOverlay: collapse per-argument comments to one line.
5. (Discuss) demo writes outliving end(): no code change; startup purge + HL7 skip are the backstop. Track VM launches if a demo row shows up after a tour.

Verify: ./gradlew.bat :app:testDebugUnitTest --tests "*PillScanningViewModelCaptureTest*" --tests "*DashboardTourOfferTest*"

## Follow-up (item 4 + test proof)
- Mutation-checked the notes-off capture test: fails without `!confirmDoneStarted`, passes with it.
- `TourGuideState.track(job)` (no-op outside a tour); `end()` cancels tracked jobs. Wrapped demo launches: DispenseFlowViewModel.useDummyRxData/onNdcBarcodeRead, InventoryScanViewModel.useDummyNdcData, PillScanningViewModel.dummyCompleteCount. Delayed second purge stays as backstop.
