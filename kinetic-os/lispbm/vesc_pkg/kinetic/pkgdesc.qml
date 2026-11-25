import QtQuick 2.15

// Kinetic OS VESC package description (format: vedderb/vesc_pkg).
// kinetic.lisp is copied here from ../../kinetic.lisp by the Makefile
// (single source of truth); do not edit the copy.
Item {
    property string pkgName: "Kinetic OS"
    property string pkgDescriptionMd: "README.md"
    property string pkgLisp: "kinetic.lisp"
    property string pkgQml: ""
    property bool pkgQmlIsFullscreen: false
    property string pkgOutput: "kinetic.vescpkg"

    // Motor controllers only (excludes VESC BMS and VESC Express).
    function isCompatible (fwRxParams) {
        var hwType = fwRxParams.hwTypeStr().toLowerCase();
        if (hwType != "vesc") {
            return false;
        }
        return true;
    }
}
