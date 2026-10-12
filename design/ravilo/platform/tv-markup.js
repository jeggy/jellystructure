/* The TV surface's page: the 1920×1080 stage and its preview pickers (mockup-only; hidden + mirrored into the fence inside the shell). */
document.write('<div id="viewport"><div id="stage" data-screen-label="Ravilo Home"></div></div>'
  + '<div id="chrome">'
  + '<div class="remote-help"><kbd>↑</kbd><kbd>↓</kbd><kbd>←</kbd><kbd>→</kbd> move <span class="sep">·</span> <kbd>↵</kbd> select <span class="sep">·</span> <kbd>esc</kbd> back</div>'
  + '<div class="skinpick" id="focuspick" title="Jellystructure → Preferences → Focus detail"><b>FOCUS</b>'
  + '<button data-fd="off">Off</button><button data-fd="line">Line · L</button><button data-fd="row">Row · J</button>'
  + '<label class="msfield" title="Jellystructure → Focus detail → Wait before it appears"><input id="fddelay" type="number" min="0" step="10">ms</label></div>'
  + '<div class="skinpick" id="statepick" title="Built, never drawn (audit 2026-09-27): R212 · R280"><b>STATE</b>'
  + '<button data-st="" class="on">Normal</button><button data-st="stale">Saved content</button><button data-st="REAUTH">Sign in again</button>'
  + '<button data-st="FORBIDDEN">Not on profile</button><button data-st="GONE">Gone</button><button data-st="UNREACHABLE">Unreachable</button></div>'
  + '<div class="skinpick" id="skinpick"><b>SKIN</b>'
  + '<button data-skin="aurora" class="on">Aurora</button><button data-skin="midnight">Midnight</button><button data-skin="noir">Noir</button></div>'
  + '</div>');
