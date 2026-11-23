if (new URLSearchParams(location.search).has('sessionId')) {
  import('./main.js');
} else {
  import('./dashboard.js');
}
