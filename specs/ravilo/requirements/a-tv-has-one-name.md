# A TV has one name

Task: DEB-5711

The phone calls one TV by two names. *Play on a TV* lists it by the name the household gave it, and the
remote that opens from that list calls it by its model string: *Playing on …*, *Paused on …*, *Can't reach …*,
*… doesn't report its volume* and the header all read the model. Found in the QA lab (DEB-5665, 2026-11-26) on
Ravilo 1.73. The cause is in the backend and happens on a real TV as well.

## Where the two names come from

A Ravilo TV gives the server its name twice:

1. **At sign-in**, as the device's display name (`deviceDisplayName()` in `ravilo-ui/src/androidMain/…/RaviloRootActuals.kt`:
   `Settings.Global.DEVICE_NAME`, else `Build.MODEL`). The server stores it in `ravilo_device.display_name`
   (`RaviloDeviceService.register`) and never changes it afterwards. The fix of 2026-10-05 (`4bd197188`, R368) made a
   sign-in send the household's name instead of the model, and it says so itself: *"a device already signed in keeps
   its old name until the next one"*.
2. **On every events socket**, as `cast_name=` (R380 owner decision 2: `TvDeviceName`, the same
   `Settings.Global.DEVICE_NAME`, read when the app starts). The server keeps it only as a socket feature
   (`castname:<name>`).

The *Play on a TV* list uses the second. `mergeTvApps` (`tv/PlaybackTargets.kt`) renames the TV app's row to its
`cast_name`, so the app's row and the Cast device of that name show as one place. Everything else uses the first.
A session's place name is `placeNameOf(device)`, which is `display_name`. That covers the session row's
`target.name`, which the remote, *Playing everywhere*, *Move to…* and the admin's sessions all read.

The two names differ whenever the TV was signed in before it had the name it has now. That happens if the TV was
renamed after sign-in, if it had no name at sign-in (so the model was stored), or if it signed in before 2026-10-05.
The lab TV is the second case: it was signed in on 2026-11-25, before the lab named its devices.

(The lab's name came out as *Lab* rather than *Lab tv* because `lab.sh` passes `device_name "Lab $d"` through
`adb shell`, which drops the quotes. That is a fault in the lab, not in the product.)

## Which name wins

**The name the household gave the TV, as it is now.** The owner has decided this twice already: R368's fix of
2026-10-05 (the TV that read *BRAVIA …* in sessions and *Stue TV* in *Play on…*), and R380 decision 2 (*the TV
appears once in Play on…*, named as Cast names it). The model string is used only when the TV has no name of its
own, as it is today.

## Requirements

- **FR-1.** When a device's events socket carries a `cast_name`, the server stores it as that device's display name:
  the `ravilo_device` row for that device and signed-in user, written only when the name differs. It is the name as
  `parseCastName` cleans it. A socket without `cast_name` leaves the stored name as it is.
- **FR-2.** The new name takes effect from the socket that carries it. The device that socket registers on the events
  bus carries the new name, and so does `validateDeviceToken`'s cached copy (patched in place, as `recordAddress` does
  for the address, FR-236-6). A session started on or reported by the TV after that socket opened is therefore named by
  the new name. A session that is already live keeps the name it started with.
- **FR-3.** Nothing else changes. `mergeTvApps` still matches the Cast device by `cast_name`. Sign-in still sends
  `deviceDisplayName()`. A phone, a computer and Ravilo Web keep the name they signed in with: they send no
  `cast_name`.

No wire change: the TV already sends `cast_name` (R380), so installed TVs get the fix from a backend deploy alone. No
schema change: one `UPDATE` query on `ravilo_device`.

The remote header already cuts a long name at one line with an ellipsis (`OneRemoteScreen.kt`, `widthIn(max = 200.dp)`).
With FR-1 a named TV no longer shows its model there, and a TV with no name keeps the ellipsis.

## Not in scope

- A phone or computer renamed after sign-in keeps its old name. Fixing that means a new socket parameter
  (`device_name=`) from every client, which is a wire addition. Nobody has reported it, so it waits for a report.
- Naming a device from inside Ravilo or the admin. There is no such surface today.

## Proof

- `linuxX64Test`: a device signed in as the model string opens a socket with `cast_name=Stue TV`. After that,
  `validateDeviceToken` and the bus's device both read *Stue TV*, and a session the device reports is named *Stue TV*.
  A second socket with the same name writes nothing, and a socket with no `cast_name` keeps the stored name.
- On a device (QA): a TV signed in under one name and renamed in Settings, then Ravilo reopened on it. *Play on a TV*
  and every line on the remote show the new name.
