$(document).ready(function() {

// --- Active tab time tracking: counts only while tab is visible + focused ---
    (function () {
        let activeMs = 0;
        let activeStartMs = null;
        let sessionReported = false;

        function isActiveNow() {
            return document.visibilityState === "visible" && document.hasFocus();
        }

        function startActive() {
            if (activeStartMs === null) {
                activeStartMs = performance.now();
            }
        }

        function stopActive() {
            if (activeStartMs !== null) {
                activeMs += (performance.now() - activeStartMs);
                activeStartMs = null;
            }
        }

        function syncActiveState() {
            if (isActiveNow()) startActive();
            else stopActive();
        }

        document.addEventListener("visibilitychange", syncActiveState, true);
        window.addEventListener("focus", syncActiveState, true);
        window.addEventListener("blur", syncActiveState, true);
        window.addEventListener("pageshow", syncActiveState, true); // helps with bfcache restores

        function reportSession() {
            if (sessionReported) return;
            sessionReported = true;

            stopActive();

            const payload = JSON.stringify({ timeSpentSeconds: activeMs / 1000.0 });

            if (navigator.sendBeacon) {
                navigator.sendBeacon("/sms/session", new Blob([payload], { type: "application/json" }));
            } else {
                fetch("/sms/session", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: payload,
                    keepalive: true
                });
            }
        }

        window.addEventListener("pagehide", reportSession, true);
        window.addEventListener("beforeunload", reportSession, true);

        syncActiveState();
    })();


    function getSMS() {
        return $("textarea").val().trim()
    }

    function getGuess() {
        return $("input[name='guess']:checked").val().trim()
    }

    function cleanResult() {
        $("#result").removeClass("correct")
        $("#result").removeClass("incorrect")
        $("#result").removeClass("error")
        $("#result").html()
    }

    $("button").click(function (e) {
        e.stopPropagation()
        e.preventDefault()

        var sms = getSMS()
        var guess = getGuess()

        $.ajax({
            type: "POST",
            url: "./",
            data: JSON.stringify({"sms": sms, "guess": guess}),
            contentType: "application/json",
            dataType: "json",
            success: handleResult,
            error: handleError
        })
    })

    function handleResult(res) {
        var wasRight = res.result == getGuess()

        cleanResult()
        $("#result").addClass(wasRight ? "correct" : "incorrect")
        $("#result").html("The classifier " + (wasRight ? "agrees" : "disagrees"))
        $("#result").show()
    }

    function handleError(e) {
        cleanResult()
        $("#result").addClass("error")
        $("#result").html("An error occured (see server log).")
        $("#result").show()
    }

    $("textarea").on('keypress',function(e) {
        $("#result").hide()
    })

    $("input").click(function(e) {
        $("#result").hide()
    })
})
