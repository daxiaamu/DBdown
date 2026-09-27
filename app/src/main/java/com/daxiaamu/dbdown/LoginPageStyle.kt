package com.daxiaamu.dbdown

object LoginPageStyle {
    // The official H5 page absolutely positions its legal text across the login button
    // at a 360 CSS-pixel viewport. Only restore normal flow; never modify the form.
    const val BILI = """
        (function() {
            if (location.hostname !== 'passport.bilibili.com' ||
                !location.pathname.startsWith('/h5-app/passport/login')) return;
            if (document.getElementById('dbdown-login-layout') || !document.head) return;
            var style = document.createElement('style');
            style.id = 'dbdown-login-layout';
            style.textContent = '.login-wrap .explain-tips{position:static!important;margin:16px 0!important;}';
            document.head.appendChild(style);
        })();
    """
}
