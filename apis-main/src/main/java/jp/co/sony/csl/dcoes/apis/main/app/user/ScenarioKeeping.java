package jp.co.sony.csl.dcoes.apis.main.app.user;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.time.LocalDateTime;
import java.time.LocalTime;

import jp.co.sony.csl.dcoes.apis.common.Error;
import jp.co.sony.csl.dcoes.apis.common.ServiceAddress;
import jp.co.sony.csl.dcoes.apis.common.util.DateTimeUtil;
import jp.co.sony.csl.dcoes.apis.common.util.vertx.JsonObjectUtil;
import jp.co.sony.csl.dcoes.apis.common.util.vertx.JsonObjectWrapper;
import jp.co.sony.csl.dcoes.apis.common.util.vertx.VertxConfig;
import jp.co.sony.csl.dcoes.apis.main.util.ErrorUtil;

/**
 * SCENARIO を管理する Verticle.
 * {@link User} Verticle から起動される.
 * @author OES Project
 */
public class ScenarioKeeping extends AbstractVerticle {
	private static final Logger log = LoggerFactory.getLogger(ScenarioKeeping.class);

	/**
	 * ファイルシステムから SCENARIO を読み込む周期のデフォルト値 [ms].
	 * 値は {@value}.
	 */
	private static final Long LOCAL_FILE_DEFAULT_REFRESHING_PERIOD_MSEC = 5000L;
	/**
	 * サービスセンタから SCENARIO を取得する周期のデフォルト値 [ms].
	 * 値は {@value}.
	 */
	private static final Long CONTROL_CENTER_DEFAULT_REFRESHING_PERIOD_MSEC = 60000L;

	/**
	 * ファイルシステムの SCENARIO を保持しておくキャッシュ.
	 */
	private static final JsonObjectWrapper localFileCache_ = new JsonObjectWrapper();
	/**
	 * サービスセンタの SCENARIO を保持しておくキャッシュ.
	 */
	private static final JsonObjectWrapper controlCenterCache_ = new JsonObjectWrapper();

	/**
	 * SCENARIO を保持しておくキャッシュを取得する.
	 * サービスセンタのキャッシュがあればそちらを優先する.
	 * なければローカルファイルのキャッシュを返す.
	 * @return SCENARIO のキャッシュ.
	 */
	public static JsonObjectWrapper cache() {
		return (!controlCenterCache_.isNull()) ? controlCenterCache_ : localFileCache_;
	}

	private String localFilePath_;
	private boolean controlCenterEnabled_ = false;
	private String controlCenterAccount_;
	private String controlCenterPassword_;
	private long localFileReadingTimerId_ = 0L;
	private long controlCenterAccessingTimerId_ = 0L;
	private boolean stopped_ = false;

	/**
	 * 起動時に呼び出される.
	 * CONFIG から設定を取得し初期化する.
	 * - CONFIG.scenarioFile : SCENARIO ファイルのパス
	 * - CONFIG.controlCenter.enabled : サービスセンタ機能を使用するかフラグ [{@link Boolean}]
	 * - CONFIG.controlCenter.account : サービスセンタの認証アカウント
	 * - CONFIG.controlCenter.password : サービスセンタ認証パスワード ( MD5 ハッシュ済み文字列 )
	 * {@link io.vertx.core.eventbus.EventBus} サービスを起動する.
	 * ローカルファイルを定期的に再読み込みするタイマを起動する.
	 * サービスセンタ機能を使用する場合, 定期的にアクセスするタイマを起動する.
	 * @param startFuture {@inheritDoc}
	 * @throws Exception {@inheritDoc}
	 */
	@Override public void start(Future<Void> startFuture) throws Exception {
		localFilePath_ = VertxConfig.config.getString("scenarioFile");
		controlCenterEnabled_ = VertxConfig.config.getBoolean(Boolean.TRUE, "controlCenter", "enabled");
		if (controlCenterEnabled_) {
			controlCenterAccount_ = VertxConfig.config.getString("controlCenter", "account");
			controlCenterPassword_ = VertxConfig.config.getString("controlCenter", "password");
		}
		if (log.isInfoEnabled()) log.info("scenarioFile : " + localFilePath_);
		if (log.isInfoEnabled()) log.info("scenarioFile.defaultRefreshingPeriodMsec : " + LOCAL_FILE_DEFAULT_REFRESHING_PERIOD_MSEC);
		if (log.isInfoEnabled()) log.info("controlCenter.enabled : " + controlCenterEnabled_);
		if (log.isInfoEnabled()) log.info("controlCenter.account : " + controlCenterAccount_);
		if (log.isInfoEnabled()) log.info("controlCenter.defaultRefreshingPeriodMsec : " + CONTROL_CENTER_DEFAULT_REFRESHING_PERIOD_MSEC);

		startScenarioService_(resScenario -> {
			if (resScenario.succeeded()) {
				localFileReadingTimerHandler_(0L);
				// サービスセンタの機能を使用するならタイマを仕込む
				if (controlCenterEnabled_) {
					controlCenterAccessingTimerHandler_(0L);
				}
				if (log.isTraceEnabled()) log.trace("started : " + deploymentID());
				startFuture.complete();
			} else {
				startFuture.fail(resScenario.cause());
			}
		});
	}

	/**
	 * 停止時に呼び出される.
	 * タイマを止めるためのフラグを立てる.
	 * @throws Exception {@inheritDoc}
	 */
	@Override public void stop() throws Exception {
		stopped_ = true;
		if (log.isTraceEnabled()) log.trace("stopped : " + deploymentID());
	}

	////

	/**
	 * {@link io.vertx.core.eventbus.EventBus} サービス起動.
	 * アドレス : {@link ServiceAddress.User#scenario()}
	 * 範囲 : ローカル
	 * 処理 : 自ユニットの SCENARIO を取得する.
	 * 　　   定期的にリフレッシュされているキャッシュから指定した時刻における設定を返す.
	 * メッセージボディ : 日時 [{@link String}]
	 * 　　　　　　　　   APIS 標準フォーマット ( uuuu/MM/dd-HH:mm:ss )
	 * メッセージヘッダ : なし
	 * レスポンス : 指定した時刻における SCENARIO サブセット [{@link JsonObject}]
	 * 　　　　　   見つからない場合は fail.
	 * 　　　　　   エラーが起きたら fail.
	 * @param completionHandler the completion handler
	 */
	private void startScenarioService_(Handler<AsyncResult<Void>> completionHandler) {
		vertx.eventBus().<String>localConsumer(ServiceAddress.User.scenario(), req -> {
			JsonObject globalAcceptSelection = cache().getJsonObject("acceptSelection");
			LocalDateTime dt = DateTimeUtil.toLocalDateTime(req.body());
			if (dt != null) {
				LocalTime t = dt.toLocalTime();
				String hhmmss = DateTimeUtil.toString(t);
				JsonObject jsonObject = cache().jsonObject();
				if (jsonObject != null) {
					for (String aKey : jsonObject.fieldNames()) {
						// 時間帯ごとの設定は "時時:分分:秒秒-時時:分分:秒秒" というフォーマットのキーで登録されている
						String[] fromTo = aKey.split("-", 2);
						if (fromTo.length == 2) {
							if (fromTo[0].compareTo(hhmmss) <= 0 && hhmmss.compareTo(fromTo[1]) < 0) {
								// "開始時分秒" 以上 "終了時分秒" 未満なら
								JsonObject result = jsonObject.getJsonObject(aKey);
								if (!result.containsKey("acceptSelection") && globalAcceptSelection != null) {
									// 時間帯設定が acceptSelection を持っていない場合はグローバルの acceptSelection をコピペする
									result = result.copy().put("acceptSelection", globalAcceptSelection);
								}
								// 返す
								req.reply(result);
								return;
							}
						}
					}
					ErrorUtil.reportAndFail(vertx, Error.Category.USER, Error.Extent.LOCAL, Error.Level.WARN, "no entry matched for time : " + hhmmss, req);
				} else {
					req.fail(-1, "cache is null");
				}
			} else {
				ErrorUtil.reportAndFail(vertx, Error.Category.LOGIC, Error.Extent.LOCAL, Error.Level.ERROR, "message is null or bad value : " + req.body(), req);
			}
		}).completionHandler(completionHandler);
	}

	////

	/**
	 * ファイルシステムから SCENARIO を読み込むタイマ設定.
	 * 待ち時間は {@code SCENARIO.refreshingPeriodMsec} ( デフォルト値 {@link #LOCAL_FILE_DEFAULT_REFRESHING_PERIOD_MSEC} ).
	 */
	private void setLocalFileReadingTimer_() {
		Long delay = localFileCache_.getLong(LOCAL_FILE_DEFAULT_REFRESHING_PERIOD_MSEC, "refreshingPeriodMsec");
		setLocalFileReadingTimer_(delay);
	}
	/**
	 * ファイルシステムから SCENARIO を読み込むタイマ設定.
	 * @param delay 周期 [ms]
	 */
	private void setLocalFileReadingTimer_(long delay) {
		localFileReadingTimerId_ = vertx.setTimer(delay, this::localFileReadingTimerHandler_);
	}
	/**
	 * ファイルシステムから SCENARIO を読み込むタイマ処理.
	 * @param timerId タイマ ID
	 */
	private void localFileReadingTimerHandler_(Long timerId) {
		if (stopped_) return;
		if (null == timerId || timerId.longValue() != localFileReadingTimerId_) {
			ErrorUtil.report(vertx, Error.Category.LOGIC, Error.Extent.LOCAL, Error.Level.WARN, "illegal timerId : " + timerId + ", localFileReadingTimerId_ : " + localFileReadingTimerId_);
			return;
		}
		doReadLocalFile_(resRead -> {
			if (resRead.succeeded()) {
				// キャッシュしておく
				localFileCache_.setJsonObject(resRead.result());
			} else {
				// 読み込めなかったら
				if (localFileCache_.isNull()) {
					// キャッシュが空なら ( 動けないので ) エラーにする
					ErrorUtil.report(vertx, Error.Category.USER, Error.Extent.LOCAL, Error.Level.ERROR, resRead.cause());
				} else {
					// キャッシュがあったら ( 動けるので ) スルー
					ErrorUtil.report(vertx, Error.Category.USER, Error.Extent.LOCAL, Error.Level.WARN, resRead.cause());
				}
			}
			setLocalFileReadingTimer_();
		});
	}

	private void doReadLocalFile_(Handler<AsyncResult<JsonObject>> completionHandler) {
		vertx.fileSystem().readFile(localFilePath_, resFile -> {
			if (resFile.succeeded()) {
				JsonObjectUtil.toJsonObject(resFile.result(), resToJsonObject -> {
					if (resToJsonObject.succeeded()) {
						JsonObject jsonObject = resToJsonObject.result();
						completionHandler.handle(Future.succeededFuture(jsonObject));
					} else {
						completionHandler.handle(resToJsonObject);
					}
				});
			} else {
				completionHandler.handle(Future.failedFuture(resFile.cause()));
			}
		});
	}

	////

	/**
	 * サービスセンタから SCENARIO を取得するタイマ設定.
	 * 待ち時間は {@code SCENARIO.refreshingPeriodMsec} ( デフォルト値 {@link #CONTROL_CENTER_DEFAULT_REFRESHING_PERIOD_MSEC} ).
	 */
	private void setControlCenterAccessingTimer_() {
		Long delay = controlCenterCache_.getLong(CONTROL_CENTER_DEFAULT_REFRESHING_PERIOD_MSEC, "refreshingPeriodMsec");
		setControlCenterAccessingTimer_(delay);
	}
	/**
	 * サービスセンタから SCENARIO を取得するタイマ設定.
	 * @param delay 周期 [ms]
	 */
	private void setControlCenterAccessingTimer_(long delay) {
		controlCenterAccessingTimerId_ = vertx.setTimer(delay, this::controlCenterAccessingTimerHandler_);
	}
	/**
	 * サービスセンタから SCENARIO を取得するタイマ処理.
	 * @param timerId タイマ ID
	 */
	private void controlCenterAccessingTimerHandler_(Long timerId) {
		if (stopped_) return;
		if (null == timerId || timerId.longValue() != controlCenterAccessingTimerId_) {
			ErrorUtil.report(vertx, Error.Category.LOGIC, Error.Extent.LOCAL, Error.Level.WARN, "illegal timerId : " + timerId + ", controlCenterAccessingTimerId_ : " + controlCenterAccessingTimerId_);
			return;
		}
		DeliveryOptions options = new DeliveryOptions().addHeader("account", controlCenterAccount_).addHeader("password", controlCenterPassword_);
		vertx.eventBus().<JsonObject>send(ServiceAddress.ControlCenterClient.scenario(), null, options, resScenario -> {
			if (resScenario.succeeded()) {
				// キャッシュしておく
				controlCenterCache_.setJsonObject(resScenario.result().body());
			} else {
				ErrorUtil.report(vertx, Error.Category.USER, Error.Extent.LOCAL, Error.Level.WARN, "Communication failed on EventBus", resScenario.cause());
			}
			setControlCenterAccessingTimer_();
		});
	}

}
