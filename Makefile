STATIC=public/*.html public/*.png public/*.css public/*.svg public/*.gif

all: server.js build

server.js: src/**/*.cljs shadow-cljs.edn
	npx shadow-cljs release server

test.js: src/**/*.cljs shadow-cljs.edn
	npx shadow-cljs release tests

build: src/**/* $(STATIC)
	mkdir -p build
	cp -L $(STATIC) build
	npx shadow-cljs release --debug app
	touch build

test: test.js
	node test.js

.PHONY: watch watcher server repl

server:
	rm -f devserver.js
	until [ -f devserver.js ]; do sleep 1; done
	sleep 1 && while [ 1 ]; do node devserver.js; sleep 3; done

watcher:
	npx shadow-cljs watch server app

watch:
	make -j2 watcher server

repl:
	npx shadow-cljs cljs-repl app
