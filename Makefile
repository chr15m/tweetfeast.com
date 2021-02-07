server.js: src/**/*.cljs shadow-cljs.edn
	npx shadow-cljs release server

test.js: src/**/*.cljs shadow-cljs.edn
	npx shadow-cljs release tests

test: test.js
	node test.js

.PHONY: watch watcher server

server:
	rm -f devserver.js
	until [ -f devserver.js ]; do sleep 1; done
	sleep 1 && node devserver.js

watcher:
	npx shadow-cljs watch server app

watch:
	make -j2 watcher server

