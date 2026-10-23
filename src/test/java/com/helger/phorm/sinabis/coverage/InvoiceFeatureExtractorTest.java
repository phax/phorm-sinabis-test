/*
 * Copyright (C) 2026 Philip Helger (www.helger.com)
 * philip[at]helger[dot]com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.helger.phorm.sinabis.coverage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.Test;

import com.helger.base.io.nonblocking.NonBlockingByteArrayInputStream;

/**
 * Test class for class {@link InvoiceFeatureExtractor}.
 *
 * @author Philip Helger
 */
public final class InvoiceFeatureExtractorTest
{
  private static final String XML = """
      <rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
          xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100"
          xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100">
        <rsm:ExchangedDocument>
          <ram:TypeCode>381</ram:TypeCode>
        </rsm:ExchangedDocument>
        <rsm:SupplyChainTradeTransaction>
          <ram:IncludedSupplyChainTradeLineItem>
            <ram:SpecifiedLineTradeDelivery>
              <ram:BilledQuantity unitCode="HUR">-2.00</ram:BilledQuantity>
            </ram:SpecifiedLineTradeDelivery>
          </ram:IncludedSupplyChainTradeLineItem>
          <ram:IncludedSupplyChainTradeLineItem />
          <ram:ApplicableHeaderTradeAgreement>
            <ram:ContractReferencedDocument><ram:IssuerAssignedID>C1</ram:IssuerAssignedID></ram:ContractReferencedDocument>
          </ram:ApplicableHeaderTradeAgreement>
          <ram:ApplicableHeaderTradeSettlement>
            <ram:InvoiceCurrencyCode>SEK</ram:InvoiceCurrencyCode>
                                          <ram:TaxCurrencyCode>EUR</ram:TaxCurrencyCode>
                                          <ram:ApplicableTradeTax><ram:CategoryCode>AE</ram:CategoryCode></ram:ApplicableTradeTax>
            <ram:SpecifiedTradeAllowanceCharge>
              <ram:ChargeIndicator><udt:Indicator>true</udt:Indicator></ram:ChargeIndicator>
            </ram:SpecifiedTradeAllowanceCharge>
            <ram:SpecifiedTradeSettlementPaymentMeans><ram:TypeCode>58</ram:TypeCode></ram:SpecifiedTradeSettlementPaymentMeans>
            <ram:SpecifiedTradeSettlementHeaderMonetarySummation>
              <ram:TotalPrepaidAmount>0.00</ram:TotalPrepaidAmount>
              <ram:DuePayableAmount>0.00</ram:DuePayableAmount>
            </ram:SpecifiedTradeSettlementHeaderMonetarySummation>
          </ram:ApplicableHeaderTradeSettlement>
        </rsm:SupplyChainTradeTransaction>
      </rsm:CrossIndustryInvoice>
      """;

  @Test
  public void testExtract () throws Exception
  {
    final Set <String> aFeatures = new InvoiceFeatureExtractor ().extract (new NonBlockingByteArrayInputStream (XML.getBytes (StandardCharsets.UTF_8)));
    assertTrue (aFeatures.contains ("Document: type code 381"));
    assertTrue (aFeatures.contains ("Lines: 2-5 lines"));
    assertTrue (aFeatures.contains ("Lines: quantity < 0"));
    assertTrue (aFeatures.contains ("VAT: category AE"));
    assertTrue (aFeatures.contains ("Document: currency SEK"));
    assertTrue (aFeatures.contains ("VAT: tax currency EUR"));
    assertTrue (aFeatures.contains ("Payment: means code 58"));
    assertTrue (aFeatures.contains ("References: contract reference"));
    assertTrue (aFeatures.contains ("Allowance/charge/amounts: document charge"));
    assertTrue (aFeatures.contains ("Allowance/charge/amounts: payable amount = 0"));
    // Prepaid amount is 0, so not covered
    assertFalse (aFeatures.contains ("Allowance/charge/amounts: prepaid amount"));
    assertFalse (aFeatures.contains ("Allowance/charge/amounts: document allowance"));
  }

  @Test
  public void testDescribe () throws Exception
  {
    final Set <String> aFeatures = new InvoiceFeatureExtractor ().extract (new NonBlockingByteArrayInputStream (XML.getBytes (StandardCharsets.UTF_8)));
    final String s = InvoiceFeatureExtractor.describe (aFeatures);
    assertTrue (s, s.startsWith ("Credit note (381) in SEK with "));
    assertEquals (-1, s.indexOf ("type code"));
  }
}
