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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.jspecify.annotations.NonNull;

/**
 * Extracts the covered aspects of a CII invoice, in the spirit of the KoSIT XRechnung test suite
 * overview. A feature is a string <code>Group: label</code>. The static features describe which
 * optional parts are present, the dynamic ones (type code, VAT category, ...) carry the value in
 * the label.
 * <p>
 * Instances are not thread safe because the underlying {@link XMLInputFactory} is not guaranteed to
 * be - use one per thread.
 * </p>
 *
 * @author Philip Helger
 */
public final class InvoiceFeatureExtractor
{
  // Path prefixes - relative to the root element
  private static final String TX = "SupplyChainTradeTransaction/";
  private static final String AGR = TX + "ApplicableHeaderTradeAgreement/";
  private static final String DEL = TX + "ApplicableHeaderTradeDelivery/";
  private static final String SET = TX + "ApplicableHeaderTradeSettlement/";
  private static final String LINE = TX + "IncludedSupplyChainTradeLineItem/";
  private static final String LINE_AGR = LINE + "SpecifiedLineTradeAgreement/";
  private static final String LINE_SET = LINE + "SpecifiedLineTradeSettlement/";
  private static final String SUM = SET + "SpecifiedTradeSettlementHeaderMonetarySummation/";
  private static final String TERMS = SET + "SpecifiedTradePaymentTerms/";
  private static final String MEANS = SET + "SpecifiedTradeSettlementPaymentMeans/";
  private static final String SELLER = AGR + "SellerTradeParty/";
  private static final String BUYER = AGR + "BuyerTradeParty/";

  /** Group names, in output order */
  public static final String G_DOC = "Document";
  public static final String G_LINES = "Lines";
  public static final String G_VAT = "VAT";
  public static final String G_PAYMENT = "Payment";
  public static final String G_REF = "References";
  public static final String G_PARTY = "Parties";
  public static final String G_DELIVERY = "Delivery";
  public static final String G_PERIOD = "Period";
  public static final String G_AMOUNTS = "Allowance/charge/amounts";
  public static final String G_LINEDETAILS = "Line details";

  private final XMLInputFactory m_aXIF = XMLInputFactory.newFactory ();

  public InvoiceFeatureExtractor ()
  {
    m_aXIF.setProperty (XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
    m_aXIF.setProperty (XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
  }

  /**
   * Parse the invoice into paths and values.
   *
   * @param aIS
   *        The invoice XML. Not closed.
   * @return The parsed data
   * @throws XMLStreamException
   *         If the XML is not well-formed
   */
  @NonNull
  public InvoiceData parse (@NonNull final InputStream aIS) throws XMLStreamException
  {
    final InvoiceData ret = new InvoiceData ();
    final XMLStreamReader aReader = m_aXIF.createXMLStreamReader (aIS);
    try
    {
      final Deque <String> aPaths = new ArrayDeque <> ();
      final Deque <StringBuilder> aTexts = new ArrayDeque <> ();
      final Deque <boolean []> aHasChild = new ArrayDeque <> ();
      while (aReader.hasNext ())
      {
        switch (aReader.next ())
        {
          case XMLStreamConstants.START_ELEMENT:
          {
            final String sName = aReader.getLocalName ();
            final String sPath;
            if (aPaths.isEmpty ())
              sPath = "";
            else
            {
              final String sParent = aPaths.peek ();
              sPath = sParent.isEmpty () ? sName : sParent + "/" + sName;
              aHasChild.peek ()[0] = true;
            }
            aPaths.push (sPath);
            aTexts.push (new StringBuilder ());
            aHasChild.push (new boolean [1]);
            ret.addElement (sPath);
            for (int i = 0; i < aReader.getAttributeCount (); i++)
            {
              final String sAttrPath = sPath + "@" + aReader.getAttributeLocalName (i);
              ret.addElement (sAttrPath);
              ret.addValue (sAttrPath, aReader.getAttributeValue (i));
            }
            break;
          }
          case XMLStreamConstants.CHARACTERS:
          case XMLStreamConstants.CDATA:
            aTexts.peek ().append (aReader.getText ());
            break;
          case XMLStreamConstants.END_ELEMENT:
          {
            final String sPath = aPaths.pop ();
            final String sText = aTexts.pop ().toString ().trim ();
            final boolean bHasChild = aHasChild.pop ()[0];
            if (!bHasChild && !sText.isEmpty ())
              ret.addValue (sPath, sText);
            break;
          }
          default:
            break;
        }
      }
    }
    finally
    {
      aReader.close ();
    }
    return ret;
  }

  /**
   * Parse and extract the features.
   *
   * @param aIS
   *        The invoice XML. Not closed.
   * @return The features, sorted
   * @throws XMLStreamException
   *         If the XML is not well-formed
   * @throws IOException
   *         On read errors
   */
  @NonNull
  public Set <String> extract (@NonNull final InputStream aIS) throws XMLStreamException, IOException
  {
    return extract (parse (aIS));
  }

  private static void _add (final Set <String> aSet, final String sGroup, final String sLabel, final boolean bCondition)
  {
    if (bCondition)
      aSet.add (sGroup + ": " + sLabel);
  }

  @NonNull
  private static String _linesBucket (final int n)
  {
    if (n <= 1)
      return "1 line";
    if (n <= 5)
      return "2-5 lines";
    if (n <= 20)
      return "6-20 lines";
    return "more than 20 lines";
  }

  /**
   * Derive the features from the parsed data.
   *
   * @param d
   *        The parsed invoice
   * @return The features, sorted
   */
  @NonNull
  public static Set <String> extract (@NonNull final InvoiceData d)
  {
    final Set <String> ret = new TreeSet <> ();

    // Document
    for (final String s : d.values ("ExchangedDocument/TypeCode"))
      _add (ret, G_DOC, "type code " + s, true);
    for (final String s : d.values (SET + "InvoiceCurrencyCode"))
      _add (ret, G_DOC, "currency " + s, true);
    _add (ret, G_DOC, "invoice note", d.has ("ExchangedDocument/IncludedNote"));
    _add (ret, G_DOC, "invoice note with subject code", d.has ("ExchangedDocument/IncludedNote/SubjectCode"));
    _add (ret, G_DOC, "buyer reference", d.has (AGR + "BuyerReference"));
    _add (ret, G_DOC, "payment reference (remittance information)", d.has (SET + "PaymentReference"));
    _add (ret, G_DOC, "business process ID", d.has ("ExchangedDocumentContext/BusinessProcessSpecifiedDocumentContextParameter/ID"));

    // Lines
    final int nLines = d.count (TX + "IncludedSupplyChainTradeLineItem");
    _add (ret, G_LINES, _linesBucket (nLines), nLines > 0);
    _add (ret, G_LINES, "line note", d.has (LINE + "AssociatedDocumentLineDocument/IncludedNote"));
    _add (ret,
          G_LINES,
          "quantity < 0",
          d.numbers (LINE + "SpecifiedLineTradeDelivery/BilledQuantity").stream ().anyMatch (x -> x.signum () < 0));
    _add (ret,
          G_LINES,
          "line amount < 0",
          d.numbers (LINE_SET + "SpecifiedTradeSettlementLineMonetarySummation/LineTotalAmount")
           .stream ()
           .anyMatch (x -> x.signum () < 0));
    _add (ret,
          G_LINES,
          "net price = 0",
          d.numbers (LINE_AGR + "NetPriceProductTradePrice/ChargeAmount")
           .stream ()
           .anyMatch (x -> x.signum () == 0));
    _add (ret, G_LINES, "line period", d.has (LINE_SET + "BillingSpecifiedPeriod"));
    _add (ret, G_LINES, "line allowance", d.hasValue (LINE_SET + "SpecifiedTradeAllowanceCharge/ChargeIndicator/Indicator", "false"));
    _add (ret, G_LINES, "line charge", d.hasValue (LINE_SET + "SpecifiedTradeAllowanceCharge/ChargeIndicator/Indicator", "true"));
    _add (ret, G_LINES, "order line reference", d.has (LINE_AGR + "BuyerOrderReferencedDocument/LineID"));
    _add (ret, G_LINES, "invoiced object identifier on line", d.has (LINE_AGR + "AdditionalReferencedDocument"));

    // Line details
    final String PROD = LINE + "SpecifiedTradeProduct/";
    _add (ret, G_LINEDETAILS, "item description", d.has (PROD + "Description"));
    _add (ret, G_LINEDETAILS, "seller item identification", d.has (PROD + "SellerAssignedID"));
    _add (ret, G_LINEDETAILS, "buyer item identification", d.has (PROD + "BuyerAssignedID"));
    _add (ret, G_LINEDETAILS, "standard item identification", d.has (PROD + "GlobalID"));
    _add (ret, G_LINEDETAILS, "item classification", d.has (PROD + "DesignatedProductClassification"));
    _add (ret, G_LINEDETAILS, "item origin country", d.has (PROD + "OriginTradeCountry"));
    _add (ret, G_LINEDETAILS, "additional item property", d.has (PROD + "ApplicableProductCharacteristic"));
    _add (ret, G_LINEDETAILS, "gross price", d.has (LINE_AGR + "GrossPriceProductTradePrice"));
    _add (ret, G_LINEDETAILS, "price basis quantity", d.has (LINE_AGR + "NetPriceProductTradePrice/BasisQuantity"));

    // VAT
    final int nHeaderTax = d.count (SET + "ApplicableTradeTax");
    _add (ret, G_VAT, nHeaderTax + " tax breakdown(s)", nHeaderTax > 0);
    for (final String s : new TreeSet <> (d.values (SET + "ApplicableTradeTax/CategoryCode")))
      _add (ret, G_VAT, "category " + s, true);
    final int nCategories = new TreeSet <> (d.values (SET + "ApplicableTradeTax/CategoryCode")).size ();
    _add (ret, G_VAT, nCategories + " different category code(s)", nCategories > 0);
    for (final String s : new TreeSet <> (d.values (SET + "ApplicableTradeTax/RateApplicablePercent")))
      _add (ret, G_VAT, "rate " + s + "%", true);
    _add (ret, G_VAT, "exemption reason text", d.has (SET + "ApplicableTradeTax/ExemptionReason"));
    _add (ret, G_VAT, "exemption reason code", d.has (SET + "ApplicableTradeTax/ExemptionReasonCode"));
    _add (ret, G_VAT, "tax point date (BT-7)", d.has (SET + "ApplicableTradeTax/TaxPointDate"));
    _add (ret, G_VAT, "VAT due date type code (BT-8)", d.has (SET + "ApplicableTradeTax/DueDateTypeCode"));
    _add (ret, G_VAT, "tax currency", d.has (SET + "TaxCurrencyCode"));
    for (final String s : new TreeSet <> (d.values (SET + "TaxCurrencyCode")))
      _add (ret, G_VAT, "tax currency " + s, true);
    _add (ret, G_VAT, "VAT total in tax currency", d.count (SUM + "TaxTotalAmount") > 1);
    _add (ret, G_VAT, "seller VAT identifier", d.hasValue (SELLER + "SpecifiedTaxRegistration/ID@schemeID", "VA"));
    _add (ret, G_VAT, "seller tax number", d.hasValue (SELLER + "SpecifiedTaxRegistration/ID@schemeID", "FC"));
    _add (ret, G_VAT, "buyer VAT identifier", d.hasValue (BUYER + "SpecifiedTaxRegistration/ID@schemeID", "VA"));
    _add (ret, G_VAT, "tax representative", d.has (AGR + "SellerTaxRepresentativeTradeParty"));

    // Payment
    for (final String s : new TreeSet <> (d.values (MEANS + "TypeCode")))
      _add (ret, G_PAYMENT, "means code " + s, true);
    _add (ret, G_PAYMENT, d.count (MEANS) + " payment means", d.count (MEANS) > 1);
    _add (ret, G_PAYMENT, "credit transfer account", d.has (MEANS + "PayeePartyCreditorFinancialAccount"));
    _add (ret, G_PAYMENT, "payee BIC", d.has (MEANS + "PayeeSpecifiedCreditorFinancialInstitution/BICID"));
    _add (ret, G_PAYMENT, "payment card", d.has (MEANS + "ApplicableTradeSettlementFinancialCard"));
    _add (ret, G_PAYMENT, "debited account (direct debit)", d.has (MEANS + "PayerPartyDebtorFinancialAccount"));
    _add (ret, G_PAYMENT, "payment means information text", d.has (MEANS + "Information"));
    _add (ret, G_PAYMENT, "payment terms text", d.has (TERMS + "Description"));
    _add (ret, G_PAYMENT, "due date", d.has (TERMS + "DueDateDateTime"));
    _add (ret, G_PAYMENT, "structured payment discount (Skonto)", d.has (TERMS + "ApplicableTradePaymentDiscountTerms"));
    _add (ret, G_PAYMENT, "direct debit mandate ID", d.has (TERMS + "DirectDebitMandateID"));
    _add (ret, G_PAYMENT, "bank assigned creditor ID", d.has (SET + "CreditorReferenceID"));
    _add (ret, G_PAYMENT, "payee party", d.has (SET + "PayeeTradeParty"));

    // References
    _add (ret, G_REF, "order reference", d.has (AGR + "BuyerOrderReferencedDocument"));
    _add (ret, G_REF, "sales order reference", d.has (AGR + "SellerOrderReferencedDocument"));
    _add (ret, G_REF, "contract reference", d.has (AGR + "ContractReferencedDocument"));
    _add (ret, G_REF, "project reference", d.has (AGR + "SpecifiedProcuringProject"));
    _add (ret, G_REF, "tender or lot reference", d.hasValue (AGR + "AdditionalReferencedDocument/TypeCode", "50"));
    _add (ret, G_REF, "invoiced object identifier", d.hasValue (AGR + "AdditionalReferencedDocument/TypeCode", "130"));
    _add (ret, G_REF, "additional document reference", d.has (AGR + "AdditionalReferencedDocument/IssuerAssignedID"));
    _add (ret, G_REF, "additional document binary object", d.has (AGR + "AdditionalReferencedDocument/AttachmentBinaryObject"));
    _add (ret, G_REF, "preceding invoice reference", d.has (SET + "InvoiceReferencedDocument"));
    _add (ret, G_REF, "despatch advice reference", d.has (DEL + "DespatchAdviceReferencedDocument"));
    _add (ret, G_REF, "receiving advice reference", d.has (DEL + "ReceivingAdviceReferencedDocument"));

    // Parties
    _add (ret, G_PARTY, "seller contact", d.has (SELLER + "DefinedTradeContact"));
    _add (ret, G_PARTY, "buyer contact", d.has (BUYER + "DefinedTradeContact"));
    _add (ret, G_PARTY, "seller legal registration ID", d.has (SELLER + "SpecifiedLegalOrganization/ID"));
    _add (ret, G_PARTY, "buyer legal registration ID", d.has (BUYER + "SpecifiedLegalOrganization/ID"));
    _add (ret, G_PARTY, "seller electronic address", d.has (SELLER + "URIUniversalCommunication/URIID"));
    _add (ret, G_PARTY, "buyer electronic address", d.has (BUYER + "URIUniversalCommunication/URIID"));
    _add (ret, G_PARTY, "seller identifier", d.has (SELLER + "ID") || d.has (SELLER + "GlobalID"));
    _add (ret, G_PARTY, "2+ seller identifiers", d.count (SELLER + "ID") + d.count (SELLER + "GlobalID") > 1);
    _add (ret, G_PARTY, "buyer identifier", d.has (BUYER + "ID") || d.has (BUYER + "GlobalID"));
    _add (ret, G_PARTY, "seller trading name", d.has (SELLER + "SpecifiedLegalOrganization/TradingBusinessName"));
    for (final String s : new TreeSet <> (d.values (SELLER + "PostalTradeAddress/CountryID")))
      _add (ret, G_PARTY, "seller country " + s, true);
    for (final String s : new TreeSet <> (d.values (BUYER + "PostalTradeAddress/CountryID")))
      _add (ret, G_PARTY, "buyer country " + s, true);

    // Delivery
    _add (ret, G_DELIVERY, "delivery date", d.has (DEL + "ActualDeliverySupplyChainEvent"));
    _add (ret, G_DELIVERY, "delivery party name", d.has (DEL + "ShipToTradeParty/Name"));
    _add (ret, G_DELIVERY, "delivery location identifier", d.has (DEL + "ShipToTradeParty/ID") || d.has (DEL + "ShipToTradeParty/GlobalID"));
    _add (ret, G_DELIVERY, "delivery address", d.has (DEL + "ShipToTradeParty/PostalTradeAddress"));

    // Period
    _add (ret, G_PERIOD, "invoice period", d.has (SET + "BillingSpecifiedPeriod"));
    _add (ret, G_PERIOD, "invoice period start only", d.has (SET + "BillingSpecifiedPeriod/StartDateTime") && !d.has (SET + "BillingSpecifiedPeriod/EndDateTime"));
    _add (ret, G_PERIOD, "invoice period end only", d.has (SET + "BillingSpecifiedPeriod/EndDateTime") && !d.has (SET + "BillingSpecifiedPeriod/StartDateTime"));

    // Amounts
    _add (ret, G_AMOUNTS, "document allowance", d.hasValue (SET + "SpecifiedTradeAllowanceCharge/ChargeIndicator/Indicator", "false"));
    _add (ret, G_AMOUNTS, "document charge", d.hasValue (SET + "SpecifiedTradeAllowanceCharge/ChargeIndicator/Indicator", "true"));
    _add (ret, G_AMOUNTS, "rounding amount", d.has (SUM + "RoundingAmount"));
    _add (ret,
          G_AMOUNTS,
          "prepaid amount",
          d.numbers (SUM + "TotalPrepaidAmount").stream ().anyMatch (x -> x.signum () != 0));
    final List <BigDecimal> aDue = d.numbers (SUM + "DuePayableAmount");
    _add (ret, G_AMOUNTS, "payable amount = 0", aDue.stream ().anyMatch (x -> x.signum () == 0));
    _add (ret, G_AMOUNTS, "payable amount < 0", aDue.stream ().anyMatch (x -> x.signum () < 0));

    return ret;
  }

  /**
   * @param aFeatures
   *        The features of {@link #extract(InvoiceData)}
   * @return A single-sentence description like in the KoSIT test overview, built from the features.
   */
  @NonNull
  public static String describe (@NonNull final Set <String> aFeatures)
  {
    final Set <String> aParts = new LinkedHashSet <> ();
    String sType = "Invoice";
    for (final String s : aFeatures)
      if (s.startsWith (G_DOC + ": type code "))
      {
        final String sCode = s.substring ((G_DOC + ": type code ").length ());
        sType = switch (sCode)
        {
          case "380" -> "Standard invoice";
          case "381" -> "Credit note";
          case "384" -> "Corrected invoice";
          case "389" -> "Self-billed invoice";
          case "875" -> "Partial construction invoice";
          case "876" -> "Partial final construction invoice";
          case "877" -> "Final construction invoice";
          default -> "Document type " + sCode;
        };
        sType += " (" + sCode + ")";
      }
    // The "with" part: all features of the structural groups, minus the pure "value" features
    for (final String s : aFeatures)
    {
      if (s.startsWith (G_DOC + ": type code ") ||
          s.startsWith (G_DOC + ": currency ") ||
          s.startsWith (G_VAT + ": rate ") ||
          s.startsWith (G_PARTY + ": seller country ") ||
          s.startsWith (G_PARTY + ": buyer country ") ||
          s.endsWith ("different category code(s)"))
        continue;
      aParts.add (s.substring (s.indexOf (": ") + 2));
    }
    final String sCurrencyPrefix = G_DOC + ": currency ";
    for (final String s : aFeatures)
      if (s.startsWith (sCurrencyPrefix))
        sType += " in " + s.substring (sCurrencyPrefix.length ());
    return sType + " with " + String.join (", ", aParts);
  }
}
