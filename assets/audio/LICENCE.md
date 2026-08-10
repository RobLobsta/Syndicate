# Audio licence

Every file in this directory is **procedurally synthesised** by
`asset-pipeline`'s `SoundBankBuilder` (`docs/15_vehicle_preparation_pipeline.md#D15-S8`).

- **Source:** generated, not sampled or recorded. No third-party audio was used.
- **Licence:** the same terms as this repository. No attribution to any third party
  is required, and no third-party terms apply.
- **Reproducible:** regenerating from the same seed produces byte-identical files, so
  a rebuild is a no-op diff.

This matters because D15-R39 requires every audio asset to record its licence, and the
two shipped vehicle models are CC-BY-NC-SA. That constraint is already live; the point
of synthesising the audio is that it does not add a second, differently-encumbered set
of terms for somebody to discover later.

To regenerate: `./gradlew :asset-pipeline:buildSoundBank`
