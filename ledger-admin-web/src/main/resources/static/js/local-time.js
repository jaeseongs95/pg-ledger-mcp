// Shows every <time datetime="...Z"> (UTC from the server) in the time zone of the browser.
// The server never converts a time zone; without this script the pages show the UTC text.
(function () {
  'use strict';
  var format = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' });
  var times = document.querySelectorAll('time[datetime]');
  for (var i = 0; i < times.length; i++) {
    var time = times[i];
    var instant = new Date(time.getAttribute('datetime'));
    if (!isNaN(instant.getTime())) {
      time.title = time.getAttribute('datetime');
      time.textContent = format.format(instant);
    }
  }
})();
