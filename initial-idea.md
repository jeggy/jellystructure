Let's create the requirements for a nice web system that can be hosted via docker compose and is built via Kotlin with Kotlin native on backend and kotlin wasm on frontend. We want the frontend to be using proper dom things instead of some canvas like Kotlin Composed.

This system should be configured via config file and possibility to update the config directly from the website. Some important settings are:
* TMDB API KEY
* Jellyfin url and api key
* path to the directories locally that contains the jellyfin media
* Probably some more as well

This system should be a replacement for the existing metadata that jellyfin provides, so you shouldn't need to use the refresh metadata ever in the jellyfin system, but rather maintain the whole thing from this system.

We want to maintain nfo files, all images (posters, backgrounds, logos, etc) and even maintain the actual media files, like re-ordering audio tracks and subtitles in the files themselves, so we can control which language comes first in the list.

We want to create a general rule of fetching metadata for each media in the language that the media is in. So if a file has the languages 0 -> Faroese, 1 -> Danish. Then via TMDB api, we will first check faroese and if it's not there, then check Danish, and if that isn't there either, then we'll check for the global fallback (controlled via some global config (but default is english)).
For this to work properly, we want to have an overview page of all the media files and audio tracks that they have and list all the ones that have audio tracks without a language attached to it's metadata. This way we can fix it manually via the website.

The website should feel a bit modern, so we want changes in there to happen in real-time.

We want to build this project in multiple steps, so let's write the requirements and all the different phases.
