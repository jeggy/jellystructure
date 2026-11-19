# The server address ignores stray whitespace, and shows a pasted line break instead of hiding it

Task: DEB-5002

> Owner, 2026-11-18: *"When setting the server on the android app in the login flow. And having a whitespace at the
> end, then it doesn't work. Please fix that"*
>
> And, the same evening: *"When pasting a url in. It might look like this: `jelly.example.net` / `[21:20]` … there is a
> new line and a time on the newline. But this isn't visible, so even though everything looks correct, it's incorrect
> and we should have some nice visualization on this … We do not want to make the input field into a multi line field.
> So some nice validation and auto fix button could be nice here."*

## Scope

The Compose server-setup screen (`ravilo-ui/…/screens/ServerSetupScreen.kt`), which every Compose client draws —
Android TV, phone, Ravilo Web, macOS and Linux. It amends nothing R226 promised: R226 FR-3 already checks a *trimmed*
host, but the URL it builds is the untrimmed one, so `jelly.example.net␣` becomes `https://jelly.example.net␣` and the
login that follows reports the server unreachable.

What the code does today, read 2026-11-19: `fullUrl` is `"https://$host"` on the field's raw text (no `trim()`);
`canConnect` is `host.isNotBlank()`; `RaviloRoot` saves the URL as given and every later start reads it back
verbatim. `addressForEditing` (R349 FR-R349-8) already trims, so *Wrong server?* shows a clean address — which is why
the fault looks like nothing is wrong. A Compose `TextField` with `singleLine = true` still keeps a pasted `\n` and
everything after it in its value; it just doesn't draw it, which is the owner's second report.

Not in scope: the Samsung TV's setup screen (`ravilo-screen/…/ScreenSetup.kt`) already trims, and an HTML text input
drops line breaks itself, so it has neither fault. No host-format checking beyond whitespace — R226's non-goal stands.

## Requirements

Throughout, a **hidden character** is any whitespace (`Char.isWhitespace()`, which covers spaces, tabs, `\r`, `\n`,
no-break space and the Unicode line/paragraph separators) or one of the invisible format characters U+200B, U+200C,
U+200D, U+2060, U+FEFF. A **line break** is `\r`, `\n`, U+2028 or U+2029.

### FR-1: Hidden characters at either end are dropped, silently

Before the scheme is inferred (R226 FR-R226-2), hidden characters at the start and end of the field's text are removed,
and then any trailing `/`. `jelly.example.net␣` connects to `https://jelly.example.net`; `␣http://10.0.0.5:8097/␣`
connects to `http://10.0.0.5:8097`. Nothing is shown for this: it is what a browser's address bar does, and there is
no reading of a trailing space that the viewer meant.

The derivation is one pure function in `commonMain` (`serverUrlFromAddress(text): String?` — null when the result is
empty), so it is tested once and the screen, the IME *Go* action and *Connect* all call it.

### FR-2: Connect is enabled only for an address FR-1 leaves non-empty

A field holding only hidden characters keeps *Connect* disabled, as an empty one does today.

### FR-3: An address saved before this fix is read back clean

A device that saved `https://jelly.example.net␣` before FR-1 shipped has it applied to the saved value when `RaviloRoot`
reads it on start, so it signs in after the update without the viewer re-entering anything. The saved value is
rewritten clean the first time it is read.

### FR-4: A hidden character inside the address is shown, under the field

When the text still contains a hidden character after FR-1 — a pasted line break with a timestamp after it, or a space
in the middle — the field stays one line, and directly under it the screen shows:

- one sentence saying what is wrong: *"This address has a hidden line break"* when it contains a line break, otherwise
  *"This address has a hidden space"*;
- the address as typed, on one line, with each hidden character drawn as a visible mark (a line break as `⏎`, any other
  hidden character as `·`) in the warning colour, so the viewer sees where the text went wrong.

It updates as the viewer types and disappears as soon as the text is clean. How it looks is the design round's
(see *Design*); the content above is not.

### FR-5: One button fixes it, and names the result

Next to the warning, a button labelled *"Use jelly.example.net"* — the cleaned address — replaces the field's text with
it. The cleaned address is the **first line that is not blank**, with every hidden character in it removed. Pressing it
leaves the field holding exactly that text, the warning disappears, and focus moves to *Connect*. On a TV the D-pad
order is field → fix button → *Connect*, and Up reverses it.

### FR-6: A hidden character is never connected to silently

While FR-4's warning is showing, *Connect* is disabled and the keyboard's *Go* does nothing. The viewer either presses
the FR-5 button or edits the text.

### FR-7: The words are in the string table

Three keys in `i18n/en.json`, `da.json` and `fo.json` (R279):

| Key | en | da | fo |
| --- | --- | --- | --- |
| `setup.hidden_line_break` | This address has a hidden line break | Adressen har et skjult linjeskift | Adressan hevur eitt fjalt linjuskift |
| `setup.hidden_space` | This address has a hidden space | Adressen har et skjult mellemrum | Adressan hevur eitt fjalt millumrúm |
| `setup.use_fixed_address` | Use {address} | Brug {address} | Brúka {address} |

## Design

FR-1 to FR-3 change nothing a viewer sees, and are built first. FR-4 and FR-5 add a line and a button to the setup
screen. A design round draws them on phone, TV and desktop before they are built.

## Verification

- `serverUrlFromAddress` unit-tested in `ravilo-ui` `commonTest` (run by `:ravilo-ui:desktopTest`): trailing space,
  leading tab, trailing `/`, `http://` kept, only whitespace → null, and the cleaned-address function from FR-5 on
  `jelly.example.net\n[21:20]` → `jelly.example.net`, `jelly. example.net` → `jelly.example.net`, a leading blank line,
  and a U+200B in the middle.
- On a phone: paste an address with a trailing space and connect. Then paste the two-line example and check that the
  warning, the marks and the fix button appear, and that pressing the button lets the viewer connect.
