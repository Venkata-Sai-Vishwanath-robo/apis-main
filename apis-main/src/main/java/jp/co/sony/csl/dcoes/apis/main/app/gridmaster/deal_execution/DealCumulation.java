package jp.co.sony.csl.dcoes.apis.main.app.gridmaster.deal_execution;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.List;

import jp.co.sony.csl.dcoes.apis.common.Deal;
import jp.co.sony.csl.dcoes.apis.common.Error;
import jp.co.sony.csl.dcoes.apis.common.util.vertx.JsonObjectUtil;
import jp.co.sony.csl.dcoes.apis.main.app.controller.util.DDCon;
import jp.co.sony.csl.dcoes.apis.main.app.mediator.util.DealUtil;
import jp.co.sony.csl.dcoes.apis.main.util.ErrorExceptionUtil;
import jp.co.sony.csl.dcoes.apis.main.util.ErrorUtil;

/**
 * 融通電力量を積算する.
 * 終了条件に到達したら停止する.
 * @author OES Project
 */
public class DealCumulation extends AbstractStoppableDealExecution {
	private static final Logger log = LoggerFactory.getLogger(DealCumulation.class);

	/**
	 * インスタンスを生成する.
	 * @param vertx vertx オブジェクト
	 * @param policy POLICY オブジェクト. 処理中に変更されても影響しないように {@link jp.co.sony.csl.dcoes.apis.main.app.gridmaster.main_loop.DealExecution DealExecution} 開始時にコピーしたものが渡される.
	 * @param deal 処理対象 DEAL オブジェクト
	 * @param otherDeals 同時に存在している他の DEAL オブジェクトのリスト
	 */
	public DealCumulation(Vertx vertx, JsonObject policy, JsonObject deal, List<JsonObject> otherDeals) {
		super(vertx, policy, deal, otherDeals);
	}
	/**
	 * インスタンスを生成する.
	 * 他の {@link AbstractDealExecution} の内部状態をそのまま受け継ぐため初期化不要.
	 * @param other 他の abstractdealexecution オブジェクト
	 */
	public DealCumulation(AbstractDealExecution other) {
		super(other);
	}

	@Override protected void doExecute(Handler<AsyncResult<Void>> completionHandler) {
		if (DDCon.Mode.WAIT != masterSideUnitDDConMode_()) {
			if (DDCon.Mode.WAIT != slaveSideUnitDDConMode_()) {
				// 融通電力の積算計算をする
				cumulateDeal_(resCumulateDeal -> {
					if (resCumulateDeal.succeeded()) {
						Float cumulateAmountWh = deal_.getFloat("cumulateAmountWh");
						Integer dealAmountWh = deal_.getInteger("dealAmountWh");
						if (dealAmountWh < cumulateAmountWh) {
							// 計算結果が約束した融通量を超えたら
							// 電圧リファレンス側じゃない方のユニットと止め
							stopDcdc_(resStopDcdc -> {
								if (resStopDcdc.succeeded()) {
									// 融通情報を stop 状態にし
									stopDeal_(resStopDeal -> {
										if (resStopDeal.succeeded()) {
											// 融通終了処理に移行する
											new DealDeactivation(this).execute(completionHandler);
										} else {
											completionHandler.handle(resStopDeal);
										}
									});
								} else {
									completionHandler.handle(resStopDcdc);
								}
							});
						} else {
							// 送電側のバッテリ最小残量と受電側のバッテリ最大残量をチェックし
							Float dischargeUnitLowerLimitRsoc = JsonObjectUtil.getFloat(policy_, "gridMaster", "deal", "forceStopCondition", "dischargeUnitLowerLimitRsoc");
							Float chargeUnitUpperLimitRsoc = JsonObjectUtil.getFloat(policy_, "gridMaster", "deal", "forceStopCondition", "chargeUnitUpperLimitRsoc");
							if (dischargeUnitLowerLimitRsoc != null && chargeUnitUpperLimitRsoc != null) {
								Float dischargeUnitRsoc = JsonObjectUtil.getFloat(dischargeUnitData_, "battery", "rsoc");
								Float chargeUnitRsoc = JsonObjectUtil.getFloat(chargeUnitData_, "battery", "rsoc");
								if (dischargeUnitRsoc != null && chargeUnitRsoc != null) {
									String abortReason = null;
									if (dischargeUnitRsoc < dischargeUnitLowerLimitRsoc) {
										abortReason = dischargeUnitId_ + " : dischargeUnitLowerLimitRsoc";
									} else if (chargeUnitUpperLimitRsoc < chargeUnitRsoc) {
										abortReason = chargeUnitId_ + " : chargeUnitUpperLimitRsoc";
									}
									if (abortReason != null) {
										// "送りすぎ" "もらいすぎ" が起きていたら fail する → DealExecution が Abort してくれる
										completionHandler.handle(Future.failedFuture(abortReason));
									} else {
										if (log.isInfoEnabled()) log.info("deal goes on ...");
										completionHandler.handle(Future.succeededFuture());
									}
								} else {
									ErrorUtil.reportAndFail(vertx_, Error.Category.LOGIC, Error.Extent.GLOBAL, Error.Level.WARN, "no battery.rsoc value in discharge and/or charge unit data : " + dischargeUnitData_ + ", " + chargeUnitData_, completionHandler);
								}
							} else {
								ErrorUtil.reportAndFail(vertx_, Error.Category.USER, Error.Extent.LOCAL, Error.Level.ERROR, "data deficiency; dischargeUnitLowerLimitRsoc : " + dischargeUnitLowerLimitRsoc + ", chargeUnitUpperLimitRsoc : " + chargeUnitUpperLimitRsoc + " in POLICY.gridMaster.deal.forceStopCondition values", completionHandler);
							}
						}
					} else {
						completionHandler.handle(resCumulateDeal);
					}
				});
			} else {
				ErrorUtil.reportAndFail(vertx_, Error.Category.LOGIC, Error.Extent.GLOBAL, Error.Level.WARN, "invalid slave side unit status ; unit : " + slaveSideUnitId_() + ", mode : " + slaveSideUnitDDConMode_(), completionHandler);
			}
		} else {
			ErrorUtil.reportAndFail(vertx_, Error.Category.LOGIC, Error.Extent.GLOBAL, Error.Level.WARN, "invalid master side unit status ; unit : " + masterSideUnitId_() + ", mode : " + masterSideUnitDDConMode_(), completionHandler);
		}
	}

	/**
	 * 融通電力積算.
	 * @param completionHandler the completion handler
	 */
	private void cumulateDeal_(Handler<AsyncResult<Void>> completionHandler) {
		// リファレンス側のバッテリ電力を取得し
		Float unitWb = referenceUnitWb_();
		if (unitWb != null) {
			if (log.isInfoEnabled()) log.info("reference unit wb : " + unitWb);
			if (existOtherActiveDealsOnReferenceUnit_()) {
				// リファレンス側ユニットが他の active な融通に参加していたら → めんどくさい計算をする
				float sumOfOtherDealIgs = sumOfOtherDealCompensatedGridCurrentAs_(referenceUnitId_());
				Float dealIg = Deal.compensatedGridCurrentA(deal_, referenceUnitId_());
				if (dealIg != null && sumOfOtherDealIgs + dealIg != 0F) { // ぬるぽとゼロ割のチェック
					// 当該融通の融通電力 = リファレンス側のバッテリ電力 x 当該融通のグリッド電流 / 全融通のグリッド電流の合計
					float dealWb = unitWb * dealIg / (sumOfOtherDealIgs + dealIg);
					if (log.isInfoEnabled()) log.info("sum of other deal compensated ig : " + sumOfOtherDealIgs + ", deal compensated ig : " + dealIg + " ; deal wb : " + dealWb);
					// DEAL オブジェクトに対し融通電力積算を計算する
					DealUtil.cumulate(vertx_, deal_, referenceDateTimeString_(), dealWb, resCumulate -> ErrorExceptionUtil.reportIfNeedAndHandle(vertx_, resCumulate, completionHandler));
				} else {
					ErrorUtil.reportAndFail(vertx_, Error.Category.LOGIC, Error.Extent.GLOBAL, Error.Level.ERROR, "no reference side compensatedGridCurrentA in deal or sum of deal compensated ig is zero ; unit id : " + referenceUnitId_() + ", deal : " + deal_, completionHandler);
				}
			} else {
				// リファレンス側ユニットが他の融通に参加していなければ → 計算は単純
				// DEAL オブジェクトに対し融通電力積算を計算する
				DealUtil.cumulate(vertx_, deal_, referenceDateTimeString_(), unitWb, resCumulate -> ErrorExceptionUtil.reportIfNeedAndHandle(vertx_, resCumulate, completionHandler));
			}
		} else {
			ErrorUtil.reportAndFail(vertx_, Error.Category.LOGIC, Error.Extent.GLOBAL, Error.Level.WARN, "no dcdc.meter.wb value in reference unit data ; unit data : " + referenceUnitData_() + ", deal : " + deal_, completionHandler);
		}
	}
	/**
	 * リファレンス側ユニットが他の active な融通に参加しているか否かを取得する.
	 * @return 参加していれば {@code true}
	 */
	private boolean existOtherActiveDealsOnReferenceUnit_() {
		for (JsonObject aDeal : otherDeals_(referenceUnitId_())) {
			if (Deal.isMasterSideUnit(aDeal, referenceUnitId_(), masterSide_)) {
				if (Deal.masterSideUnitMustBeActive(aDeal)) {
					return true;
				}
			} else {
				if (Deal.slaveSideUnitMustBeActive(aDeal)) {
					return true;
				}
			}
		}
		return false;
	}

}
