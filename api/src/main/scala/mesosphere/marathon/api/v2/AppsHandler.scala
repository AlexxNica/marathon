package mesosphere.marathon
package api.v2

import akka.event.EventStream
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import mesosphere.marathon.api._
import mesosphere.marathon.api.v2.AppsResource.{ NormalizationConfig, authzSelector }
import mesosphere.marathon.api.v2.Validation.validateOrThrow
import mesosphere.marathon.api.v2.validation.AppValidation
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.plugin.auth.{ CreateRunSpec, Identity }
import mesosphere.marathon.raml.AppConversion
import mesosphere.marathon.state.{ AppDefinition, Identifiable }
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }

trait AppsHandler extends BaseHandler with LeaderDirectives
    with AuthDirectives with ValidationDirectives with PlayJson with AppConversion {

  import AppsHandler._

  def clock: Clock
  def eventBus: EventStream
  def appTasksRes: AppTasksResource
  def service: MarathonSchedulerService
  def appInfoService: AppInfoService
  def config: MarathonConf
  def groupManager: GroupManager
  def pluginManager: PluginManager
  implicit val executor: ExecutionContext

  import mesosphere.marathon.api.v2.json.Formats._

  def index(cmd: Option[String], id: Option[String], label: Option[String], embed: Set[String])(implicit identity: Identity): Future[Seq[AppInfo]] = {
    def containCaseInsensitive(a: String, b: String): Boolean = b.toLowerCase contains a.toLowerCase

    val selectors = Seq[Option[Selector[AppDefinition]]](
      cmd.map(c => Selector(_.cmd.exists(containCaseInsensitive(c, _)))),
      id.map(s => Selector(app => containCaseInsensitive(s, app.id.toString))),
      label.map(new LabelSelectorParsers().parsed),
      Some(authzSelector)
    ).flatten
    val resolvedEmbed = InfoEmbedResolver.resolveApp(embed) + AppInfo.Embed.Counts + AppInfo.Embed.Deployments
    appInfoService.selectAppsBy(Selector.forall(selectors), resolvedEmbed)
  }

  def create(app: AppDefinition, force: Boolean)(implicit identity: Identity): Future[(DeploymentPlan, AppInfo)] = {

    def createOrThrow(opt: Option[AppDefinition]) = opt
      .map(_ => throw ConflictingChangeException(s"An app with id [${app.id}] already exists."))
      .getOrElse(app)

    groupManager.updateApp(app.id, createOrThrow, app.version, force).map { plan =>
      val appWithDeployments = AppInfo(
        app,
        maybeCounts = Some(TaskCounts.zero),
        maybeTasks = Some(Seq.empty),
        maybeDeployments = Some(Seq(Identifiable(plan.id)))
      )
      plan -> appWithDeployments
    }
  }

  private val normalizationConfig = AppNormalization.Configure(config.defaultNetworkName.get, config.mesosBridgeName())
  private implicit val normalizeApp: Normalization[raml.App] =
    appNormalization(NormalizationConfig(config.availableFeatures, normalizationConfig))(AppNormalization.withCanonizedIds())
  private implicit lazy val validateApp = AppDefinition.validAppDefinition(config.availableFeatures)(pluginManager)
  private implicit lazy val updateValidator = AppValidation.validateCanonicalAppUpdateAPI(config.availableFeatures)

  protected val apps: Route =
    asLeader {
      path("v2" / "apps") {
        authenticated { implicit identity =>
          get {
            parameters('cmd.?, 'id.?, 'label.?, 'embed.*) { (cmd, id, label, embed) =>
              onSuccess(index(cmd, id, label, embed.toSet))(apps => complete(Json.obj("apps" -> apps)))
            }
          } ~
            post {
              validEntityRaml(as[raml.App], normalizeApp, appRamlReader, validateApp) { app =>
                authorized(CreateRunSpec, app, identity) {
                  parameters('force.as[Boolean].?(false)) { force =>
                    onSuccess(create(app, force)){ (plan, app) =>
                      //TODO: post ApiPostEvent
                      complete((StatusCodes.Created, Seq(Deployment(plan)), app))
                    }
                  }
                }
              }
            }
        }
      }
    }
}

object AppsHandler {

  def appNormalization(config: NormalizationConfig): Normalization[raml.App] = Normalization { app =>
    validateOrThrow(app)(AppValidation.validateOldAppAPI)
    val migrated = AppNormalization.forDeprecated(config.config).normalized(app)
    validateOrThrow(migrated)(AppValidation.validateCanonicalAppAPI(config.enabledFeatures))
    AppNormalization(config.config).normalized(migrated)
  }
}