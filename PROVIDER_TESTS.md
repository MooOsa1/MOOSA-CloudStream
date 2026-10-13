# MOOSA provider testing checklist

Target device: Fire TV Stick 4K. No paid subscription or Real-Debrid.

## Catalog categories
- Foreign: films and series, new and classic
- Arabic: Gulf, Egyptian and other Arabic-language films and series
- Indian: Hindi and other Indian-language films and series

## Minimum requirements
- Lawfully available, free-to-watch source
- Original-language audio whenever available
- Arabic subtitles when available, with manual language selection preferred
- 1080p or 4K if actually supplied
- Working playback on the user's Fire TV

## Verification record template
| Provider | Foreign | Arabic | Indian | Original audio | Arabic subtitles | 1080p | 4K | Fire TV test | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| YouTube (official extensions catalog) | Varies | Varies | Varies | Varies | Varies | Varies | Varies | User confirmed playback | Playback only confirmed |
| Internet Archive (official extensions catalog) | Some public-domain/archive titles | Some archived Arabic content | Some archived Indian content | Varies by item | Varies by item | Varies | Varies | User confirmed playback | Playback only confirmed |
| Dailymotion (official extensions catalog) | Varies | Varies | Varies | Varies | Varies | Varies | Varies | User confirmed playback | Playback only confirmed |

A successful extension installation is **not** proof of legal availability, a complete film catalog, correct subtitles, reliable streams or 4K quality.

Do not publish untested providers as verified. Only add a provider to the custom plugin list after it has a compatible built .cs3 artifact and a working manifest entry.

## Link quality and playback verification
- Read direct-file quality from the provider's media metadata (height or video resolution). For HLS, expand the master playlist and use each variant's parsed height; preserve the required authorization, user-agent, and referer headers on every child playlist and segment request.
- Keep the quality unknown when the source provides no resolution evidence. Never infer 720p or FHD from a server name, file label, or the fact that a link was returned.
- A source test must reject HTML challenge/error pages masquerading as playlists, check that the selected variant playlist and an initial media segment are reachable, and then confirm playback on Fire TV before calling the source verified.
- Record separate states for link discovered, manifest/metadata quality confirmed, stream probe passed, and Fire TV playback confirmed. A passing HTTP probe alone is not proof of stable playback or actual displayed resolution.
- For series, verify the exact title, season, and episode before testing links. Do not use the series premiere year or title similarity alone to pick an episode.
