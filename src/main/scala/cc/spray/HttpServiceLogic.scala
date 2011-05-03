/*
 * Copyright (C) 2011 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray

import http._
import StatusCodes._
import HttpHeaders._
import MediaTypes._
import utils.{Rfc1123, IllegalResponseException}

/**
 * The logic part of the [[cc.spray.HttpService]]. Contains the code for [[cc.spray.RequestContext]] creation as well
 * as translation of [[cc.spray.Rejection]]s and Exceptions to [[cc.spray.http.HttpResponse]]s. 
 */
trait HttpServiceLogic extends ErrorHandling {
  
  def setDateHeader: Boolean
  
  def route: Route
  
  def handle(request: HttpRequest) {
    val context = contextForRequest(request)
    try {
      route(context)
    } catch {
      case e: IllegalResponseException => throw e
      case e: Exception => context.complete(responseForException(request, e))
    }
  }
  
  protected def contextForRequest(request: HttpRequest): RequestContext = {
    RequestContext(request, responderForRequest(request))
  }
  
  protected def responderForRequest(request: HttpRequest): RoutingResult => Unit
  
  protected[spray] def responseFromRoutingResult(rr: RoutingResult): Option[HttpResponse] = rr match {
    case Respond(httpResponse) => Some(finalizeResponse(httpResponse)) 
    case Reject(rejections) => {
      val activeRejections = Rejections.applyCancellations(rejections)
      if (activeRejections.isEmpty) None else Some(finalizeResponse(responseForRejections(activeRejections.toList)))
    }
  }
  
  protected[spray] def responseForRejections(rejections: List[Rejection]): HttpResponse = {
    def handle[R <: Rejection :Manifest]: Option[HttpResponse] = {
      val erasure = manifest.erasure
      rejections.filter(erasure.isInstance(_)) match {
        case Nil => None
        case filtered => Some(handleRejections(filtered))
      }
    }
    (handle[MethodRejection] getOrElse
    (handle[MissingQueryParamRejection] getOrElse
    (handle[MalformedQueryParamRejection] getOrElse
    (handle[AuthenticationRequiredRejection] getOrElse
    (handle[AuthorizationFailedRejection.type] getOrElse
    (handle[UnsupportedRequestContentTypeRejection] getOrElse
    (handle[RequestEntityExpectedRejection.type] getOrElse
    (handle[UnacceptedResponseContentTypeRejection] getOrElse
    (handle[MalformedRequestContentRejection] getOrElse
    (handleCustomRejections(rejections)))))))))))
  }
  
  protected def handleRejections(rejections: List[Rejection]): HttpResponse = rejections match {
    case (_: MethodRejection) :: _ => {
      // TODO: add Allow header (required by the spec)
      val methods = rejections.collect { case MethodRejection(method) => (method) }
      HttpResponse(HttpStatus(MethodNotAllowed, "HTTP method not allowed, supported methods: " + 
              methods.mkString(", ")))
    }
    case MissingQueryParamRejection(paramName) :: _ => {
      HttpResponse(HttpStatus(NotFound, "Request is missing required query parameter '" + paramName + '\''))
    }
    case MalformedQueryParamRejection(name, msg) :: _ => {
      HttpResponse(HttpStatus(BadRequest, "The query parameter '" + name + "' was malformed:\n" + msg))
    }
    case AuthenticationRequiredRejection(scheme, realm, params) :: _ => {
      HttpResponse(HttpStatus(Unauthorized, "The resource requires authentication, " +
              "which was not supplied with the request"), headers = `WWW-Authenticate`(scheme, realm, params) :: Nil)
    }
    case AuthorizationFailedRejection :: _ => {
      HttpResponse(HttpStatus(Forbidden, "The supplied authentication is either invalid " +
              "or not authorized to access this resource"))
    }
    case UnsupportedRequestContentTypeRejection(supported) :: _ => {
      HttpResponse(HttpStatus(UnsupportedMediaType, "The requests Content-Type must be one the following:\n" +
              supported.map(_.value).mkString("\n")))
    }
    case RequestEntityExpectedRejection :: _ => {
      HttpResponse(HttpStatus(BadRequest, "Request entity expected"))
    }
    case UnacceptedResponseContentTypeRejection(supported) :: _ => {
      HttpResponse(HttpStatus(NotAcceptable, "Resource representation is only available with these Content-Types:\n" +
              supported.map(_.value).mkString("\n")))
    }
    case MalformedRequestContentRejection(msg) :: _ => {
      HttpResponse(HttpStatus(BadRequest, "The request content was malformed:\n" + msg))
    }
    case _ => throw new IllegalStateException
  }
  
  protected def handleCustomRejections(rejections: List[Rejection]): HttpResponse = {
    HttpResponse(HttpStatus(InternalServerError, "Unknown request rejection: " + rejections.head))
  }
  
  protected def finalizeResponse(response: HttpResponse) = {
    val verifiedResponse = verified(response)
    val resp = if (response.isSuccess || response.content.isDefined) response 
               else response.copy(content = Some(HttpContent(response.status.reason)))
    if (setDateHeader) {
      resp.copy(headers = Date(Rfc1123.now) :: resp.headers) 
    } else resp
  }
  
  protected def verified(response: HttpResponse) = {
    response.headers.mapFind {
      _ match {
        case _: `Content-Type` => Some("HttpResponse must not include explicit 'Content-Type' header, " +
                "use the respective HttpContent member!")
        case _: `Content-Length` => Some("HttpResponse must not include explicit 'Content-Length' header, " +
                "this header will be set implicitly!")
        case _ => None
      }
    } match {
        case Some(errorMsg) => throw new IllegalResponseException(errorMsg)
        case None => response
    } 
  }
  
}