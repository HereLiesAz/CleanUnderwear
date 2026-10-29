// HISTORICAL: not used by the app. AndroidInterface.processHtml no longer exists. See scripts/README.md.

(function() {
    let lastHeight = document.body.scrollHeight;
    
    let scrollInterval = setInterval(() => {
        window.scrollTo(0, document.body.scrollHeight);
        let newHeight = document.body.scrollHeight;
        
        if (newHeight === lastHeight) {
            // Potentially reached the end, or waiting for load
            clearInterval(scrollInterval);
            // Send the full HTML back to the Android App interface
            AndroidInterface.processHtml(document.documentElement.outerHTML);
        }
        lastHeight = newHeight;
    }, 2000); // 2-second delay to allow FB to render new items
})();
