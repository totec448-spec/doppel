# Bundled persona image assets

The GNU GPL v3 in `LICENSE` covers the application source, documentation,
launcher vectors, and original integration code.

The persona image assets under

- `app/src/main/assets/character-reference/`
- `app/src/main/assets/persona-images/`
- `app/src/main/assets/persona-profile/`

are synthetic images generated for this project by the project owner using
OpenAI's GPT Image 2 model through ChatGPT, from text prompts only. No
third-party reference image was supplied to the model, no real person is
intentionally depicted, and none of them is a camera photograph.

OpenAI's [Terms of Use](https://openai.com/policies/terms-of-use/) assign the
generating user the provider's rights in model output, including for commercial
use. On that basis the project owner distributes these files under the same
GPL-3.0-only terms as the rest of this repository.

The files carry no embedded metadata. Every bundled image was checked for EXIF,
XMP, IPTC, C2PA, GPS, author and software fields and for embedded local paths;
none were present.

## What this record does not claim

It does not claim that the model outputs are themselves protected by copyright.
Several jurisdictions treat purely machine-generated images as uncopyrightable,
and this project takes no position on that. The point of the record is narrower:
the intended provenance is documented here rather than silently treating the
files as stock photography.

It does not cover images an operator imports at runtime. Approved images,
character references and profile pictures added through the app stay on the
device and are the operator's own responsibility.
