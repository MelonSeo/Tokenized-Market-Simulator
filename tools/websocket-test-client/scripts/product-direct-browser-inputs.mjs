/** Explicit trial actor's 95%-105% bounds, not server defaults or product pricing policy. */
import {units,decimal} from '../src/c-direct-intent.js';
export function trialPriceBounds(price){
 const mark=units(String(price),8);
 return {minimum:decimal((mark*95n/100n).toString(),8),maximum:decimal((mark*105n/100n).toString(),8)};
}
