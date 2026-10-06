package org.openmrs.module.initializer.api.drugs;

import org.apache.commons.collections.CollectionUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.stubbing.Answer;
import org.openmrs.ConceptMapType;
import org.openmrs.ConceptReferenceTerm;
import org.openmrs.ConceptSource;
import org.openmrs.Drug;
import org.openmrs.DrugReferenceMap;
import org.openmrs.api.ConceptService;
import org.openmrs.module.initializer.api.CsvLine;

import java.util.HashSet;
import java.util.Set;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * This kind of test case can be used to quickly trial the parsing routines on test CSVs
 */
public class MappingsDrugLineProcessorTest {
	
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
		 */
		when(cs.getConceptSourceByName(any(String.class))).thenAnswer((Answer<ConceptSource>) invocation -> {
			Object[] args = invocation.getArguments();
			String name = (String) args[0];
			ConceptSource source = new ConceptSource();
			source.setName(name);
			return source;
		});
	}
	
	@Test
	public void fill_shouldParseMappingsForTypeAndSourceInHeader() {
		
		// Setup
		String[] headerLine = { "mappings|same-as|foo" };
		String[] line = { "123" };
		
		// Replay
		MappingsDrugLineProcessor p = new MappingsDrugLineProcessor(cs);
		Drug d = p.fill(new Drug(), new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertEquals(1, d.getDrugReferenceMaps().size());
		Assert.assertEquals("foo:123", soleMappingCode(d));
	}
	
	@Test
	public void fill_shouldParseSeveralMappingsFromOneColumn() {
		
		// Setup
		String[] headerLine = { "mappings|same-as" };
		String[] line = { "cambodia:123; foo:456" };
		
		// Replay
		MappingsDrugLineProcessor p = new MappingsDrugLineProcessor(cs);
		Drug d = p.fill(new Drug(), new CsvLine(headerLine, line));
		
		// Verif
		Set<String> codes = mappingCodes(d);
		Assert.assertEquals(2, codes.size());
		Assert.assertTrue(codes.contains("cambodia:123"));
		Assert.assertTrue(codes.contains("foo:456"));
	}
	
	@Test
	public void fill_shouldHandleNoMappings() {
		
		// Setup
		String[] headerLine = { "mappings|same-as" };
		String[] line = { null };
		
		// Replay
		MappingsDrugLineProcessor p = new MappingsDrugLineProcessor(cs);
		Drug d = p.fill(new Drug(), new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertTrue(CollectionUtils.isEmpty(d.getDrugReferenceMaps()));
	}
	
	/*
	 * Regression coverage for the change that made this processor non-destructive.
	 *
	 * Upstream cleared drug.getDrugReferenceMaps() before looking at the line at all, so any row that
	 * reached it destroyed the mappings the drug already had. Reference maps are how a drug resolves
	 * from an external code -- an RxNorm code, a local formulary code -- so this is the same
	 * data-loss bug already fixed for concepts, one class over.
	 */
	
	@Test
	public void fill_shouldPreserveExistingReferenceMapsWhenTheRowDeclaresNone() {
		
		// Setup: a drug that already carries a reference map from a previous install
		String[] headerLine = { "mappings|same-as" };
		String[] line = { null };
		
		Drug d = drugWithReferenceMaps("cambodia:123");
		
		// Replay
		MappingsDrugLineProcessor p = new MappingsDrugLineProcessor(cs);
		p.fill(d, new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertEquals(1, d.getDrugReferenceMaps().size());
		Assert.assertTrue(mappingCodes(d).contains("cambodia:123"));
	}
	
	@Test
	public void fill_shouldPreserveExistingReferenceMapsWhenNoMappingColumnIsPresent() {
		
		// Setup: a drugs.csv row that only carries, say, a name
		String[] headerLine = { "name" };
		String[] line = { "Aspirin" };
		
		Drug d = drugWithReferenceMaps("cambodia:123");
		
		// Replay
		MappingsDrugLineProcessor p = new MappingsDrugLineProcessor(cs);
		p.fill(d, new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertEquals(1, d.getDrugReferenceMaps().size());
		Assert.assertTrue(mappingCodes(d).contains("cambodia:123"));
	}
	
	@Test
	public void fill_shouldPreserveEveryPreExistingReferenceMapNotJustTheFirst() {
		
		// Setup: a drug realistically carries several codes
		String[] headerLine = { "mappings|same-as" };
		String[] line = { null };
		
		Drug d = drugWithReferenceMaps("cambodia:123", "cambodia:456", "RXNORM:789", "RXNORM:abc");
		
		// Replay
		MappingsDrugLineProcessor p = new MappingsDrugLineProcessor(cs);
		p.fill(d, new CsvLine(headerLine, line));
		
		// Verif: all four survive, not just one
		Assert.assertEquals(4, d.getDrugReferenceMaps().size());
	}
	
	@Test
	public void fill_shouldStillReplaceExistingReferenceMapsWhenTheRowDeclaresThem() {
		
		// Setup: this is the upstream semantic, and it must not regress. A package that genuinely
		// authors mappings stays authoritative over them.
		String[] headerLine = { "mappings|same-as" };
		String[] line = { "cambodia:999" };
		
		Drug d = drugWithReferenceMaps("cambodia:123");
		
		// Replay
		MappingsDrugLineProcessor p = new MappingsDrugLineProcessor(cs);
		p.fill(d, new CsvLine(headerLine, line));
		
		// Verif: replaced, not merged
		Assert.assertEquals(1, d.getDrugReferenceMaps().size());
		Assert.assertTrue(mappingCodes(d).contains("cambodia:999"));
		Assert.assertFalse(mappingCodes(d).contains("cambodia:123"));
	}
	
	@Test
	public void fill_shouldStillReplaceExistingReferenceMapsForASourceInTheHeader() {
		
		// Setup: same, via the mappings|<type>|<source> column form
		String[] headerLine = { "mappings|same-as|foo" };
		String[] line = { "456" };
		
		Drug d = drugWithReferenceMaps("cambodia:123");
		
		// Replay
		MappingsDrugLineProcessor p = new MappingsDrugLineProcessor(cs);
		p.fill(d, new CsvLine(headerLine, line));
		
		// Verif
		Assert.assertEquals(1, d.getDrugReferenceMaps().size());
		Assert.assertTrue(mappingCodes(d).contains("foo:456"));
	}
	
	@Test
	public void fill_shouldNotClearReferenceMapsWhenTheMapTypeIsUnresolvable() {
		
		// Setup: an unknown map type. Upstream cleared the reference maps and then threw, so a typo in
		// a content package silently destroyed the drug's codes along with the row.
		// The shared mock above resolves any map type name, so this one has to be withdrawn.
		when(cs.getConceptMapTypeByName("no-such-map-type")).thenReturn(null);
		
		String[] headerLine = { "mappings|no-such-map-type|foo" };
		String[] line = { "456" };
		
		Drug d = drugWithReferenceMaps("cambodia:123");
		
		// Replay and verify the row still fails
		MappingsDrugLineProcessor p = new MappingsDrugLineProcessor(cs);
		try {
			p.fill(d, new CsvLine(headerLine, line));
			Assert.fail("expected an IllegalArgumentException for the unresolvable map type");
		}
		catch (IllegalArgumentException expected) {
			// the row is rejected...
		}
		
		// ...but the drug's reference maps were not collateral damage
		Assert.assertEquals(1, d.getDrugReferenceMaps().size());
		Assert.assertTrue(mappingCodes(d).contains("cambodia:123"));
	}
	
	@Test
	public void fill_shouldNotClearReferenceMapsWhenTheSourceIsUnresolvable() {
		
		// Setup: same again, one step further along, so the row fails after a map has already been
		// built. Upstream had thrown away the old reference maps by this point.
		when(cs.getConceptSourceByName("no-such-source")).thenReturn(null);
		
		String[] headerLine = { "mappings|same-as|no-such-source" };
		String[] line = { "456" };
		
		Drug d = drugWithReferenceMaps("cambodia:123");
		
		// Replay and verify the row still fails
		MappingsDrugLineProcessor p = new MappingsDrugLineProcessor(cs);
		try {
			p.fill(d, new CsvLine(headerLine, line));
			Assert.fail("expected an IllegalArgumentException for the unresolvable source");
		}
		catch (IllegalArgumentException expected) {
			// the row is rejected...
		}
		
		// ...but the drug's reference maps were not collateral damage
		Assert.assertEquals(1, d.getDrugReferenceMaps().size());
		Assert.assertTrue(mappingCodes(d).contains("cambodia:123"));
	}
	
	private static DrugReferenceMap referenceMap(String sourceAndCode) {
		
		int colon = sourceAndCode.indexOf(':');
		String sourceName = sourceAndCode.substring(0, colon);
		String code = sourceAndCode.substring(colon + 1);
		
		ConceptSource source = new ConceptSource();
		source.setName(sourceName);
		
		ConceptReferenceTerm term = new ConceptReferenceTerm();
		term.setCode(code);
		term.setConceptSource(source);
		
		// Set the map type, as the processor does. Drug.addDrugReferenceMap falls back to
		// Context.getConceptService() to pick a default when it is absent, which a plain unit test
		// has no context for.
		ConceptMapType mapType = new ConceptMapType();
		mapType.setUuid(ConceptMapType.SAME_AS_MAP_TYPE_UUID);
		mapType.setName("same-as");
		
		DrugReferenceMap map = new DrugReferenceMap();
		map.setConceptMapType(mapType);
		map.setConceptReferenceTerm(term);
		return map;
	}
	
	private static Drug drugWithReferenceMaps(String... sourceAndCodes) {
		
		Drug d = new Drug();
		for (String sourceAndCode : sourceAndCodes) {
			d.addDrugReferenceMap(referenceMap(sourceAndCode));
		}
		return d;
	}
	
	private static String soleMappingCode(Drug d) {
		
		Set<String> codes = mappingCodes(d);
		return codes.isEmpty() ? null : codes.iterator().next();
	}
	
	private static Set<String> mappingCodes(Drug d) {
		
		Set<String> codes = new HashSet<String>();
		for (DrugReferenceMap m : d.getDrugReferenceMaps()) {
			ConceptReferenceTerm term = m.getConceptReferenceTerm();
			codes.add(term.getConceptSource().getName() + ":" + term.getCode());
		}
		return codes;
	}
}
