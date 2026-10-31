package com.pricetrack.exchange.ai;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import org.junit.jupiter.api.Test;
class PilotCorpusTest {
    @Test void explicitPilotManifestAddsCWithoutReplacingFrozenNine(){
        var p=new AiProperties(true,"jdbc:postgresql://127.0.0.1:1/unreachable","none","none","../docs/ai-knowledge","../docs/ai/ingest-manifest-pilot.json","","https://api.openai.com/v1/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
        var c=new KnowledgeLoader(p,new ObjectMapper()).load();
        System.out.println("PILOT_CORPUS_DOCUMENTS="+c.documents().size()+" CHUNKS="+c.chunks().size()+" FINGERPRINT="+c.fingerprint());
        assertThat(c.documents()).hasSize(10);assertThat(c.chunks()).filteredOn(x->!x.path().equals("c-vault-read-policy.md")).hasSize(88);assertThat(c.chunks()).hasSize(93);
        assertThat(c.chunks()).filteredOn(x->x.path().equals("c-vault-read-policy.md")).allMatch(x->x.domain().equals("custody")&&x.role().equals("USER"));
    }
}
