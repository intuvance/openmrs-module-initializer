package org.openmrs.module.initializer.api.c;

import org.apache.commons.collections.CollectionUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.stubbing.Answer;
import org.openmrs.Concept;
import org.openmrs.ConceptMap;
import org.openmrs.ConceptMapType;
import org.openmrs.ConceptReferenceTerm;
import org.openmrs.ConceptSource;
import org.openmrs.api.ConceptService;
import org.openmrs.module.initializer.api.CsvLine;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * This kind of test case can be used to quickly trial the parsing routines on test CSVs
 */
public class MappingsConceptLineProcessorTest {
	
	private ConceptService cs = mock(ConceptService.class);
	
	@Before
	public void setup() {
		
		/*
		 * fetching a concept map type by uuid returns same-as if that uuid specified, null otherwise
		 */
		when(cs.getConceptMapTypeByUuid(any(String.class))).thenAnswer((Answer<ConceptMapType>) invocation -> {
			Object[] args = invocation.getArguments();
			String uuid = (String) args[0];
			ConceptMapType mapType = null;
			if (uuid.equals(ConceptMapType.SAME_AS_MAP_TYPE_UUID)) {
				mapType = new ConceptMapType();
				mapType.setUuid(uuid);
				mapType.setName("same-as");
			}
			return mapType;
		});
		
		/*
		 * fetching a concept map type by name returns a concept map type with that name
		 * if the map type is "same-as", set the uuid for this map type
		 */
		when(cs.getConceptMapTypeByName(any(String.class))).thenAnswer((Answer<ConceptMapType>) invocation -> {
			Object[] args = invocation.getArguments();
			String name = (String) args[0];
			ConceptMapType mapType = new ConceptMapType();
			if (name.equalsIgnoreCase("same-as")) {
				mapType.setUuid(ConceptMapType.SAME_AS_MAP_TYPE_UUID);
			}
			mapType.setName(name);
			return mapType;
		});
		
		/*
		 * fetching a concept source by name returns a concept source with its name set
		 * as the source string that was requested
		 */
		when(cs.getConceptSourceByName(any(String.class))).thenAnswer((Answer<ConceptSource>) invocation -> {
			Object[] args = invocation.getArguments();
			String sourceStr = (String) args[0];
			ConceptSource source = new ConceptSource();
			source.setName(sourceStr);
			return source;
		});
	}
	
	@Test
	public void fill_shouldParseSameAsMappings() {
		
		// Setup
		String[] headerLine = { "Same as mappings" };
		String[] line = { "cambodia:123; foo:456" };
		
		// Replay
		MappingsConceptLineProcessor p = new MappingsConceptLineProcessor(cs);
		Concept c = p.fill(new Concept(), new CsvLine(headerLine, line));
		
		// Verif
		Collection<ConceptMap> mappings = c.getConceptMappings();
		Assert.assertEquals(2, mappings.size());
		Set<String> names = new HashSet<String>();
		for (ConceptMap m : mappings) {
			String source = m.getConceptReferenceTerm().getConceptSource().getName();
			String code = m.getConceptReferenceTerm().getCode();
			names.add(source + ":" + code);
		}
		Assert.assertTrue(names.contains("cambodia:123"));
		Assert.assertTrue(names.contains("foo:456"));
	}
	
	@Test
	public void fill_shouldParseMappingsForTypeAndSourceInHeader() {
		
		// Setup
		String[] headerLine = { "mappings|same-as|cambodia", "mappings|broader-than|foo", "mappings|related-to",
		        "mappings|same-as|pih|code", "mappings|same-as|pih|name" };
		String[] line = { "123", "456", "cambodia:789; foo:abc", "5089", "weight" };
		
		// Replay
		MappingsConceptLineProcessor p = new MappingsConceptLineProcessor(cs);
		Concept c = p.fill(new Concept(), new CsvLine(headerLine, line));
		
		// Verif
		Collection<ConceptMap> mappings = c.getConceptMappings();
		Assert.assertEquals(6, mappings.size());
		Set<String> names = new HashSet<String>();
		for (ConceptMap m : mappings) {
			String mapType = m.getConceptMapType().getName();
			String source = m.getConceptReferenceTerm().getConceptSource().getName();
			String code = m.getConceptReferenceTerm().getCode();
			names.add(mapType + ":" + source + ":" + code);
		}
		Assert.assertTrue(names.contains("same-as:cambodia:123"));
		Assert.assertTrue(names.contains("broader-than:foo:456"));
		Assert.assertTrue(names.contains("related-to:cambodia:789"));
		Assert.assertTrue(names.contains("related-to:foo:abc"));
		Assert.assertTrue(names.contains("same-as:pih:5089"));
		Assert.assertTrue(names.contains("same-as:pih:weight"));
	}
	
	@Test
	public void fill_shouldHandleNoSameAsMappings() {
		
		// Setup
		String[] headerLine = { "Same as mappings" };
		String[] line = { null };
		
		// Replay
		MappingsConceptLineProcessor p = new MappingsConceptLineProcessor(cs);
		Concept c = p.fill(new Concept(), new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertTrue(CollectionUtils.isEmpty(c.getConceptMappings()));
	}
	
	public void getConcept_shouldHandleMissingHeaders() {
		
		// Setup
		String[] headerLine = {};
		String[] line = {};
		
		// Replay
		MappingsConceptLineProcessor p = new MappingsConceptLineProcessor(cs);
		Concept c = p.fill(new Concept(), new CsvLine(headerLine, line));
		Assert.assertNull(c.getConceptMappings());
	}
	
	/*
	 * Regression coverage for the change that made this processor non-destructive.
	 *
	 * Upstream cleared concept.getConceptMappings() before looking at the line, so any content
	 * package that merely listed a concept destroyed the mappings that concept already had. On a
	 * distribution seeded with CIEL that is a data-loss bug, not a cosmetic one.
	 */
	
	@Test
	public void fill_shouldPreserveExistingMappingsWhenTheLineDeclaresNone() {
		
		// Setup: a concept that already carries a mapping from a previous install
		String[] headerLine = { "Same as mappings" };
		String[] line = { null };
		
		Concept c = conceptWithMappings("cambodia:123");
		
		// Replay
		MappingsConceptLineProcessor p = new MappingsConceptLineProcessor(cs);
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif: the pre-existing mapping is still there
		Assert.assertEquals(1, c.getConceptMappings().size());
		Assert.assertTrue(mappingNames(c).contains("cambodia:123"));
	}
	
	@Test
	public void fill_shouldPreserveExistingMappingsWhenNoMappingColumnIsPresent() {
		
		// Setup: a content package row that only carries, say, a name
		String[] headerLine = { "Fully specified name:en" };
		String[] line = { "Bronchospasm" };
		
		Concept c = conceptWithMappings("cambodia:123");
		
		// Replay
		MappingsConceptLineProcessor p = new MappingsConceptLineProcessor(cs);
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertEquals(1, c.getConceptMappings().size());
		Assert.assertTrue(mappingNames(c).contains("cambodia:123"));
	}
	
	@Test
	public void fill_shouldPreserveExistingMappingsWhenTheMappingColumnIsPresentButEmpty() {
		
		// Setup: the column exists, but this row says nothing about mappings
		String[] headerLine = { "Same as mappings", "Fully specified name:en" };
		String[] line = { null, "Bronchospasm" };
		
		Concept c = conceptWithMappings("cambodia:123");
		
		// Replay
		MappingsConceptLineProcessor p = new MappingsConceptLineProcessor(cs);
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertEquals(1, c.getConceptMappings().size());
		Assert.assertTrue(mappingNames(c).contains("cambodia:123"));
	}
	
	@Test
	public void fill_shouldPreserveEveryPreExistingMappingNotJustTheFirst() {
		
		// Setup: the realistic case is a concept with many CIEL mappings
		String[] headerLine = { "Same as mappings" };
		String[] line = { null };
		
		Concept c = new Concept();
		for (String existing : new String[] { "CIEL:123", "CIEL:456", "SNOMED:789", "LOINC:abc" }) {
			c.addConceptMapping(mapping(existing));
		}
		
		// Replay
		MappingsConceptLineProcessor p = new MappingsConceptLineProcessor(cs);
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif: all four survive, not just one
		Assert.assertEquals(4, c.getConceptMappings().size());
	}
	
	@Test
	public void fill_shouldStillReplaceExistingMappingsWhenTheLineDeclaresThem() {
		
		// Setup: this is the upstream semantic, and it must not regress. A package that
		// genuinely authors mappings stays authoritative over them.
		String[] headerLine = { "Same as mappings" };
		String[] line = { "cambodia:999" };
		
		Concept c = conceptWithMappings("cambodia:123");
		
		// Replay
		MappingsConceptLineProcessor p = new MappingsConceptLineProcessor(cs);
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif: replaced, not merged
		Assert.assertEquals(1, c.getConceptMappings().size());
		Assert.assertTrue(mappingNames(c).contains("cambodia:999"));
		Assert.assertFalse(mappingNames(c).contains("cambodia:123"));
	}
	
	@Test
	public void fill_shouldStillReplaceExistingMappingsForAnExplicitMappingsColumn() {
		
		// Setup: same, via the mappings|<type>|<source> column form rather than "Same as mappings"
		String[] headerLine = { "mappings|same-as|foo" };
		String[] line = { "456" };
		
		Concept c = conceptWithMappings("cambodia:123");
		
		// Replay
		MappingsConceptLineProcessor p = new MappingsConceptLineProcessor(cs);
		p.fill(c, new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertEquals(1, c.getConceptMappings().size());
		Assert.assertTrue(mappingNames(c).contains("foo:456"));
	}
	
	@Test
	public void fill_shouldNotClearMappingsWhenTheMapTypeIsUnresolvable() {
		
		// Setup: an unknown map type. Upstream cleared the mappings and then threw, so a typo in
		// a content package silently destroyed the concept's mappings along with the row.
		// The shared mock above resolves any map type name, so this one has to be withdrawn.
		when(cs.getConceptMapTypeByName("no-such-map-type")).thenReturn(null);
		
		String[] headerLine = { "mappings|no-such-map-type|foo" };
		String[] line = { "456" };
		
		Concept c = conceptWithMappings("cambodia:123");
		
		// Replay and verify the row still fails
		MappingsConceptLineProcessor p = new MappingsConceptLineProcessor(cs);
		try {
			p.fill(c, new CsvLine(headerLine, line));
			Assert.fail("expected an IllegalArgumentException for the unresolvable map type");
		}
		catch (IllegalArgumentException expected) {
			// the row is rejected...
		}
		
		// ...but the concept's mappings were not collateral damage
		Assert.assertEquals(1, c.getConceptMappings().size());
		Assert.assertTrue(mappingNames(c).contains("cambodia:123"));
	}
	
	private static ConceptMap mapping(String sourceAndCode) {
		
		ConceptMapType sameAs = new ConceptMapType();
		sameAs.setUuid(ConceptMapType.SAME_AS_MAP_TYPE_UUID);
		sameAs.setName("same-as");
		
		ConceptMap m = new ConceptMap();
		m.setConceptMapType(sameAs);
		m.setConceptReferenceTerm(term(sourceAndCode));
		return m;
	}
	
	private static Concept conceptWithMappings(String... sourceAndCodes) {
		
		Concept c = new Concept();
		for (String sourceAndCode : sourceAndCodes) {
			c.addConceptMapping(mapping(sourceAndCode));
		}
		return c;
	}
	
	private static ConceptReferenceTerm term(String sourceAndCode) {
		
		int colon = sourceAndCode.indexOf(':');
		String sourceName = sourceAndCode.substring(0, colon);
		String code = sourceAndCode.substring(colon + 1);
		
		ConceptSource source = new ConceptSource();
		source.setName(sourceName);
		
		ConceptReferenceTerm term = new ConceptReferenceTerm();
		term.setCode(code);
		term.setConceptSource(source);
		return term;
	}
	
	private static Set<String> mappingNames(Concept c) {
		
		Set<String> names = new HashSet<String>();
		for (ConceptMap m : c.getConceptMappings()) {
			names.add(
			    m.getConceptReferenceTerm().getConceptSource().getName() + ":" + m.getConceptReferenceTerm().getCode());
		}
		return names;
	}
}
