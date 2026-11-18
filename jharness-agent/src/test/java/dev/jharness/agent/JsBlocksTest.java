package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class JsBlocksTest {

    @Test
    void firstJsBlock() {
        assertEquals(Optional.of("api.readFile('a')"), JsBlocks.first("Let me look.\n```js\napi.readFile('a')\n```\nthen more"));
        assertEquals(Optional.of("1\n2"), JsBlocks.first("```javascript\n1\n2\n```"));
        assertEquals(Optional.of("x"), JsBlocks.first("```JS\nx\n```"));
        assertEquals(Optional.of("a"), JsBlocks.first("```js\na\n```\n```js\nb\n```"));
    }

    @Test
    void skipsOtherBlocks() {
        assertEquals(Optional.of("run()"), JsBlocks.first("```json\n{\"a\":1}\n```\n```\nplain\n```\n```ts\nlet x: number\n```\n```js\nrun()\n```"));
        assertEquals(Optional.empty(), JsBlocks.first("All done: I fixed the bug."));
        assertEquals(Optional.empty(), JsBlocks.first("```python\nprint(1)\n```"));
        assertEquals(Optional.empty(), JsBlocks.first(null));
    }

    @Test
    void longerFencesAndTildes() {
        assertEquals(Optional.of("const s = \"```\";"), JsBlocks.first("````js\nconst s = \"```\";\n````"));
        assertEquals(Optional.of("y"), JsBlocks.first("~~~js\ny\n~~~"));
    }

    @Test
    void countsLaterJsBlocks() {
        assertEquals(Optional.of(new JsBlocks.Found("a", 0)), JsBlocks.find("```js\na\n```"));
        assertEquals(Optional.of(new JsBlocks.Found("a", 2)), JsBlocks.find("```js\na\n```\ntext\n```javascript\nb\n```\n~~~JS\nc\n~~~"));
        assertEquals(Optional.of(new JsBlocks.Found("a", 0)),
                JsBlocks.find("```json\n{}\n```\n```js\na\n```\n```ts\nb\n```\n```\nc\n```\n```python\nd\n```"));
        assertEquals(Optional.of(new JsBlocks.Found("a", 1)), JsBlocks.find("```js\na\n```\n```js\nb\n"), "an unclosed last block counts");
        assertEquals(Optional.of(new JsBlocks.Found("const s = \"```js\";", 0)), JsBlocks.find("````js\nconst s = \"```js\";\n````"));
        assertEquals(Optional.empty(), JsBlocks.find("```ts\nx\n```"));
        assertEquals(Optional.empty(), JsBlocks.find(null));
    }

    @Test
    void unclosedBlockRunsToTheEnd() {
        assertEquals(Optional.of("api.grep({pattern: 'x'})"), JsBlocks.first("```js\napi.grep({pattern: 'x'})\n"));
        assertEquals(Optional.of(""), JsBlocks.first("```js\n```"));
    }
}
