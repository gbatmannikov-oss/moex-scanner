# MOEX Native Scanner v0.8.0

Native Android source project for MOEX/ALGOPACK futures scanning.

## v0.8 trading core
- Strict sequence: H1 trend + Demand/Supply zone -> M5 liquidity sweep -> displacement/BOS -> retest.
- Sweep must take a recent swing and close back inside with rejection.
- BOS requires a close beyond the pre-break swing plus body/volume expansion.
- Retest must occur after BOS; it cannot be inferred from the BOS candle.
- Signal requires the complete sequence, matching H1 direction, and Score >= 4.5 / <= -4.5.
- TP1 risk/reward is checked; signals with RR < 1.5 are filtered to WAIT.
- Opposite BOS cancels a formed setup.
- FIZ/YUR and OI are confirmation/classification factors, not standalone triggers.
- FUTOI regime text distinguishes mixed redistribution, possible short covering, and long liquidation where data supports it.
- UI shows the FIZ/YUR/OI regime in each card.

## Security
Do not put an Algopack token in source code or send it in chat. Enter it in app settings.

## Build
This archive is source code, not a prebuilt APK. A verified APK requires an Android SDK/Gradle build environment.
