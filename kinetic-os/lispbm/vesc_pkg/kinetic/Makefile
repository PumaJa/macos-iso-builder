# Build kinetic.vescpkg. Requires VESC Tool (command line): VESC_TOOL=/path/to/vesc_tool
VESC_TOOL ?= vesc_tool
SRC := ../../kinetic.lisp

all: dist/kinetic.vescpkg

# Single source of truth: copy the script at build time.
kinetic.lisp: $(SRC)
	cp $(SRC) kinetic.lisp

# vesc_tool resolves the paths in pkgdesc.qml relative to the current directory.
kinetic.vescpkg: kinetic.lisp pkgdesc.qml README.md
	$(VESC_TOOL) --buildPkgFromDesc pkgdesc.qml

dist/kinetic.vescpkg: kinetic.vescpkg
	mkdir -p dist
	cp kinetic.vescpkg dist/kinetic.vescpkg

clean:
	rm -f kinetic.lisp kinetic.vescpkg

.PHONY: all clean
