import test from 'node:test';import assert from 'node:assert/strict';import vm from 'node:vm';
import {loginReadyExpression} from '../scripts/pilot-dom-readiness.mjs';
test('early HTML without module handlers cannot become a ready login',()=>{
 const nodes={'auth-open':{},'auth-form':{}};const document={readyState:'complete',getElementById:id=>nodes[id]};
 assert(!vm.runInNewContext(loginReadyExpression,{document}));nodes['auth-open'].onclick=()=>{};assert(!vm.runInNewContext(loginReadyExpression,{document}));nodes['auth-form'].onsubmit=()=>{};assert(vm.runInNewContext(loginReadyExpression,{document}));
 document.readyState='loading';assert(!vm.runInNewContext(loginReadyExpression,{document}));
});
