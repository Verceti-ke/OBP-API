/**
 * Open Bank Project - API
 * Copyright (C) 2011-2019, TESOBE GmbH.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Email: contact@tesobe.com
 * TESOBE GmbH.
 * Osloer Strasse 16/17
 * Berlin 13359, Germany
 *
 * This product includes software developed at
 * TESOBE (http://www.tesobe.com/)
 *
 */
package code.snippet

import java.util.Date

import code.api.UKOpenBanking.v3_1_0.UtilForUKV310
import code.api.util.{APIUtil, NewStyle}
import code.consent.{Consent, Consents}
import code.consumer.Consumers
import code.model.dataAccess.AuthUser
import code.util.Helper.MdcLoggable
import code.util.HydraUtil
import code.views.Views
import code.webuiprops.MappedWebUiPropsProvider.getWebUiPropsValue
import net.liftweb.http.{RequestVar, S, SHtml}
import net.liftweb.util.CssSel
import net.liftweb.util.Helpers._
import sh.ory.hydra.model.{AcceptConsentRequest, ConsentRequestSession, RejectRequest}

import scala.jdk.CollectionConverters.{asScalaBufferConverter, mapAsJavaMapConverter, seqAsJavaListConverter}
import com.openbankproject.commons.ExecutionContext.Implicits.global
import com.openbankproject.commons.model.{AccountId, BankId, BankIdAccountId, ViewId, ViewIdBankIdAccountId}
import com.openbankproject.commons.util.Functions.Implicits._
import net.liftweb.common.{Box, Full}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{Duration, SECONDS}

class ConsentConfirmation extends MdcLoggable {

  private object submitButtonDefenseFlag extends RequestVar("")

  private object cancelButtonDefenseFlag extends RequestVar("")


  val confirmConsentButtonValue = getWebUiPropsValue("webui_post_confirm_consent_submit_button_value", "Yes, I confirm")
  val rejectConsentButtonValue = getWebUiPropsValue("webui_post_reject_consent_submit_button_value", "Cancel")

  def confirmConsentsForm: CssSel = {

    def submitButtonDefense: Unit = {
      submitButtonDefenseFlag("true")
    }

    def cancelButtonDefense: Unit = {
      cancelButtonDefenseFlag("true")
    }


    def formElement(ele: => CssSel): CssSel =
      "form" #> {
        "type=submit" #> SHtml.submit(s"$confirmConsentButtonValue", () => submitButtonDefense) &
          "type=button" #> SHtml.submit(s"$rejectConsentButtonValue", () => cancelButtonDefense) &
          ele
      }


    val consentChallengeBox = S.param("consent_challenge")
    if (consentChallengeBox.isEmpty) {
      return formElement {
        "#confirm-errors" #> "Please login first."
      }
    }
    val consentChallenge = consentChallengeBox.orNull
    if (cancelButtonDefenseFlag.get == "true") {
      val rejectRequest = new RejectRequest()
      rejectRequest.setError("access_denied")
      rejectRequest.setErrorDescription("The resource owner denied the request")
      val rejectResponse = HydraUtil.hydraAdmin.rejectConsentRequest(consentChallenge, rejectRequest)
      AuthUser.logUserOut()
      return S.redirectTo(rejectResponse.getRedirectTo)
    }

    val consentResponse = HydraUtil.hydraAdmin.getConsentRequest(consentChallenge)

    val DateTimeRegex = """(\d+-\d{2}-\d{2}).*(\d{2}:\d{2}:\d{2}).*""".r // style example: 2020-09-09T11:55:22Z
    def getDateTime(paramName: String): Date = S.param(paramName) match {
      case Full(DateTimeRegex(date, time)) => APIUtil.DateWithSecondsFormat.parse(s"${date}T${time}Z")
      case Full(v) => throw new IllegalArgumentException(s"request parameter $paramName is not correct date time format: $v")
      case _ => throw new IllegalArgumentException(s"request parameter $paramName should not be empty.")
    }

    if (S.post_?) {
      // get values of submit form
      val consents = S.params("consent_scope")
      val bankId = S.param("bank_id")
      val accountIds = S.params("account_id")
      val fromDate = getDateTime("from_date")
      val toDate = getDateTime("to_date")
      val expirationDate = getDateTime("expiration_date")

      val currentUser = AuthUser.getCurrentUser.openOrThrowException("User is not logged in, in order to confirm consent the user must be authenticated.")

      { // TO create consent
        val accountIdsOpt = if (accountIds.isEmpty) None else Some(accountIds)
        val consent: Box[Consent] = {
          val consumer = Consumers.consumers.vend.getConsumerByConsumerKey(consentResponse.getClient.getClientId)
          val consumerId = consumer.map(_.consumerId.get)
          Consents.consentProvider.vend.saveUKConsent(Some(currentUser), bankId, accountIdsOpt, consumerId, consents, expirationDate, fromDate, toDate, Some("MXOpenFinance"), Some("0.0.1"))
        }
      }

      { // revoke all consents for all accounts

        // AuthUser.hydraConsents is just the follow values, read from props
        //ViewId: six fixed
        //"ReadAccountsBasic"
        //"ReadAccountsDetail"
        //"ReadBalances"
        //"ReadTransactionsBasic"
        //"ReadTransactionsDebits"
        //"ReadTransactionsDetail"

        val bankIdAccountIdsFuture: Future[List[BankIdAccountId]] = for {
          availablePrivateAccounts <- Views.views.vend.getPrivateBankAccountsFuture(currentUser)
          (accounts, _) <- NewStyle.function.getCoreBankAccountsFuture(availablePrivateAccounts, None)
        } yield {
          accounts.map(account => BankIdAccountId(BankId(account.bankId), AccountId(account.id)))
        }
        // all the BankIdAccountId for current user
        val bankIdAccountIds = Await.result(bankIdAccountIdsFuture, Duration(30, SECONDS))
        val revokeAccessIds: List[ViewIdBankIdAccountId] = for {
          consent <- HydraUtil.hydraConsents
          bankIdAccountId <- bankIdAccountIds
        } yield ViewIdBankIdAccountId(ViewId(consent), bankIdAccountId.bankId, bankIdAccountId.accountId)
        UtilForUKV310.revokeAccessToViews(currentUser, revokeAccessIds)
      }

      { // grant checked consents
        val grantAccessIds: List[ViewIdBankIdAccountId] = for {
          consent <- consents
          accountId <- accountIds
        } yield ViewIdBankIdAccountId(ViewId(consent), BankId(bankId.orNull), AccountId(accountId))
        UtilForUKV310.grantAccessToViews(currentUser, grantAccessIds)
      }

      // inform hydra
        val consentRequest = new AcceptConsentRequest()
        val scopes = "openid" :: "offline" :: consents
        consentRequest.setGrantScope(scopes.asJava)
        consentRequest.setGrantAccessTokenAudience(consentResponse.getRequestedAccessTokenAudience)
        consentRequest.setRemember(false)
        consentRequest.setRememberFor(3600) // TODO set in props

      val session = new ConsentRequestSession()
      val userName = currentUser.name
      val idTokenValues = Map("given_name" -> userName,
        "family_name" -> userName,
        "name" -> userName,
        "email" -> currentUser.emailAddress,
        "email_verified" -> true).asJava

      session.setIdToken(idTokenValues)
      val accessToken = Map(
        "bank_id" -> bankId.orNull,
        "account_id" -> accountIds.asJava,
        "transactionFromDateTime" -> fromDate,
        "transactionToDateTime" -> toDate,
        "expirationDateTime" -> expirationDate,
        ).asJava
      session.accessToken(accessToken)
      consentRequest.setSession(session)

      val acceptConsentResponse = HydraUtil.hydraAdmin.acceptConsentRequest(consentChallenge, consentRequest)
      S.redirectTo(acceptConsentResponse.getRedirectTo)
    } else {
      if (consentResponse.getSkip) {
        val requestBody = new AcceptConsentRequest()
        requestBody.setGrantScope(consentResponse.getRequestedScope)
        requestBody.setGrantAccessTokenAudience(consentResponse.getRequestedAccessTokenAudience)
        val requestSession = new ConsentRequestSession()
        requestBody.setSession(requestSession)
        val skipResponse = HydraUtil.hydraAdmin.acceptConsentRequest(consentChallenge, requestBody)
        S.redirectTo(skipResponse.getRedirectTo)
      } else {
        val currentUser = AuthUser.getCurrentUser.openOrThrowException("User is not login, do confirm consent must be authenticated user.")

        val bankAndAccountFuture: Future[List[(String, String, String, String)]] = for {
          availablePrivateAccounts <- Views.views.vend.getPrivateBankAccountsFuture(currentUser)
          (accounts, _) <- NewStyle.function.getCoreBankAccountsFuture(availablePrivateAccounts, None)
          (banks, _) <- NewStyle.function.getBanks(None)
        } yield {
          for {
            bank <- banks
            account <- accounts
            if account.bankId == bank.bankId.value
          } yield (bank.bankId.value, bank.shortName, account.id, account.label)
        }
        //(bankId, bankName, accountId, accountLabel)
        val bankAndAccount: List[(String, String, String, String)] = Await.result(bankAndAccountFuture, Duration(30, SECONDS))

        val banks = bankAndAccount.map(it => it._1 -> it._2).distinctBy(_._1)

        formElement {
          "#confirm-errors" #> "" &
            "#consent_challenge [value]" #> consentChallenge &
            ".bank" #> {
              banks.map { it =>
                  ".bank [value]" #> it._1 &
                  ".bank *" #> it._2
                }
            } &
            "#account_group" #> {
              bankAndAccount.map { account =>
                val (bankId, _, accountId, label) = account
                "@account_id [value]" #> accountId &
                  "@account_id [id]" #> s"account_$accountId" &
                  "@account_id [bank_id]" #> bankId &
                  "@account_id_label [for]" #> s"account_$accountId" &
                  "@account_id_label *" #> label
              }
            } &
            "#scope_group" #> consentResponse.getRequestedScope.asScala.filter(it => it != "openid" && it != "offline").map { scope =>
              "@consent_scope [value]" #> scope &
              "@consent_scope [id]" #> s"consent_$scope" &
                "@consent_scope_label [for]" #> s"consent_$scope" &
                "@consent_scope_label *" #> scope
            }
        }

      }
    }
  }
}
