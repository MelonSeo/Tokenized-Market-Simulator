import assert from 'node:assert/strict';

/** Same six frozen requests, real DOM is supplied by browser driver; unit doubles are not E2E. */
export async function runPilotSix(rows,ui,host){
  assert.equal(rows.length,6);
  assert.deepEqual(rows.map(x=>x.actor),['owner','owner','owner','other','other','other']);
  assert.deepEqual(rows.map(x=>x.case),['PA2a','PF1b','PCa','PA2a','PF1b','PCa']);
  let actor=null;let done=0;
  try{
    for(let i=0;i<rows.length;i++){
      const row=rows[i];assert.equal(row.phase,'WEB_PILOT');assert.equal(row.slot,i);
      if(row.actor!==actor){if(actor!==null){await ui.logout();await ui.assertCleared();}await ui.login(row.actor);actor=row.actor;}
      await host.before(row);
      const reply=await ui.submit(row.body);
      assert.equal(reply.http,row.expectedHttp);assert.equal(typeof reply.body.runId,'string');
      // Host verifies private worker termination and evidence; no success answer is supplied by this driver.
      await host.after(row,reply);done++;
    }
    await ui.logout();await ui.assertCleared();actor=null;
    return {requests:done,accounts:2,humanSatisfaction:false};
  }catch(error){await host.stop();throw error;}
  finally{if(actor!==null){await ui.logout();await ui.assertCleared();}}
}

/** Approved two fee retests only; same actual DOM/auth/clearance boundaries as six. */
export async function runTargetTwo(rows,ui,host){
  assert.equal(rows.length,2);
  assert.deepEqual(rows.map(x=>x.actor),['owner','other']);
  assert.deepEqual(rows.map(x=>x.case),['PF1b','PF1b']);
  let active=false,done=0;
  try{
    for(const [i,row] of rows.entries()){
      assert.equal(row.phase,'TARGET_WEB');assert.equal(row.slot,i);assert.equal(row.web,true);
      await ui.login(row.actor);active=true;
      await host.before(row);const reply=await ui.submit(row.body);
      assert.equal(reply.http,row.expectedHttp);assert.equal(typeof reply.body.runId,'string');
      await host.after(row,reply);done++;
      await ui.logout();await ui.assertCleared();active=false;
    }
    return {requests:done,accounts:2,humanSatisfaction:false,scope:'TARGET_THIRTEEN'};
  }catch(error){await host.stop();throw error;}
  finally{if(active){await ui.logout();await ui.assertCleared();}}
}
